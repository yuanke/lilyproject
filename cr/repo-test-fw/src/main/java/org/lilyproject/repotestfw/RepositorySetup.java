/*
 * Copyright 2012 NGDATA nv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.repotestfw;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.avro.ipc.NettyServer;
import org.apache.avro.ipc.Server;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.lilyproject.avro.AvroConverter;
import org.lilyproject.avro.AvroLily;
import org.lilyproject.avro.AvroLilyImpl;
import org.lilyproject.avro.LilySpecificResponder;
import org.lilyproject.hadooptestfw.HBaseProxy;
import org.lilyproject.repository.api.BlobManager;
import org.lilyproject.repository.api.BlobStoreAccess;
import org.lilyproject.repository.api.BlobStoreAccessFactory;
import org.lilyproject.repository.api.IdGenerator;
import org.lilyproject.repository.api.Repository;
import org.lilyproject.repository.api.RepositoryException;
import org.lilyproject.repository.api.TypeManager;
import org.lilyproject.repository.impl.AbstractSchemaCache;
import org.lilyproject.repository.impl.BlobManagerImpl;
import org.lilyproject.repository.impl.BlobStoreAccessConfig;
import org.lilyproject.repository.impl.DFSBlobStoreAccess;
import org.lilyproject.repository.impl.HBaseBlobStoreAccess;
import org.lilyproject.repository.impl.HBaseRepository;
import org.lilyproject.repository.impl.HBaseTypeManager;
import org.lilyproject.repository.impl.InlineBlobStoreAccess;
import org.lilyproject.repository.impl.SchemaCache;
import org.lilyproject.repository.impl.SizeBasedBlobStoreAccessFactory;
import org.lilyproject.repository.impl.id.IdGeneratorImpl;
import org.lilyproject.repository.remote.RemoteRepository;
import org.lilyproject.repository.remote.RemoteTypeManager;
import org.lilyproject.repository.spi.RecordUpdateHook;
import org.lilyproject.rowlock.HBaseRowLocker;
import org.lilyproject.rowlock.RowLocker;
import org.lilyproject.rowlog.api.RowLog;
import org.lilyproject.rowlog.api.RowLogConfig;
import org.lilyproject.rowlog.api.RowLogException;
import org.lilyproject.rowlog.api.RowLogMessageListener;
import org.lilyproject.rowlog.api.RowLogMessageListenerMapping;
import org.lilyproject.rowlog.api.RowLogSubscription;
import org.lilyproject.rowlog.impl.MessageQueueFeeder;
import org.lilyproject.rowlog.impl.RowLogConfigurationManagerImpl;
import org.lilyproject.rowlog.impl.RowLogHashShardRouter;
import org.lilyproject.rowlog.impl.RowLogImpl;
import org.lilyproject.rowlog.impl.RowLogProcessorImpl;
import org.lilyproject.rowlog.impl.RowLogShardSetup;
import org.lilyproject.rowlog.impl.WalRowLog;
import org.lilyproject.util.hbase.HBaseTableFactory;
import org.lilyproject.util.hbase.HBaseTableFactoryImpl;
import org.lilyproject.util.hbase.LilyHBaseSchema;
import org.lilyproject.util.hbase.LilyHBaseSchema.RecordCf;
import org.lilyproject.util.hbase.LilyHBaseSchema.RecordColumn;
import org.lilyproject.util.io.Closer;
import org.lilyproject.util.zookeeper.ZkUtil;
import org.lilyproject.util.zookeeper.ZooKeeperItf;

/**
 * Helper class to instantiate and wire all the repository related services.
 */
public class RepositorySetup {
    private HBaseProxy hbaseProxy;
    private Configuration hadoopConf;
    private ZooKeeperItf zk;

    private HBaseTableFactory hbaseTableFactory;

    private RowLogConfigurationManagerImpl rowLogConfManager;

    private RowLocker rowLocker;
    private IdGenerator idGenerator;
    private HBaseTypeManager typeManager;
    private RemoteTypeManager remoteTypeManager;
    private HBaseRepository repository;
    private RemoteRepository remoteRepository;

    private Server lilyServer;

    private BlobStoreAccessFactory blobStoreAccessFactory;
    private BlobStoreAccessFactory remoteBlobStoreAccessFactory;

    private BlobManager blobManager;
    private BlobManager remoteBlobManager;

    private RowLog wal;
    private RowLog mq;

    private RowLogProcessorImpl mqProcessor;

    private boolean coreSetup;
    private boolean typeManagerSetup;
    private boolean repositorySetup;

    private long hbaseBlobLimit = -1;
    private long inlineBlobLimit = -1;

    private List<RecordUpdateHook> recordUpdateHooks = Collections.emptyList();

    private RemoteTestSchemaCache remoteSchemaCache;

    public void setRecordUpdateHooks(List<RecordUpdateHook> recordUpdateHooks) {
        this.recordUpdateHooks = recordUpdateHooks;
    }

    public void setupCore() throws Exception {
        if (coreSetup)
            return;
        hbaseProxy = new HBaseProxy();
        hbaseProxy.start();
        hadoopConf = hbaseProxy.getConf();
        zk = ZkUtil.connect(hbaseProxy.getZkConnectString(), 10000);

        hbaseTableFactory = new HBaseTableFactoryImpl(hadoopConf);

        rowLocker = new HBaseRowLocker(LilyHBaseSchema.getRecordTable(hbaseTableFactory), RecordCf.DATA.bytes,
                RecordColumn.LOCK.bytes, 10000);

        coreSetup = true;
    }

    public void setupTypeManager() throws Exception {
        if (typeManagerSetup)
            return;

        idGenerator = new IdGeneratorImpl();
        typeManager = new HBaseTypeManager(idGenerator, hadoopConf, zk, hbaseTableFactory);

        typeManagerSetup = true;
    }

    public void setupRepository(boolean withWal) throws Exception {
        if (repositorySetup)
            return;

        setupTypeManager();

        blobStoreAccessFactory = createBlobAccess();
        blobManager = new BlobManagerImpl(hbaseTableFactory, blobStoreAccessFactory, false);

        if (withWal) {
            setupRowLogConfigurationManager();
            HBaseRowLocker rowLocker =
                    new HBaseRowLocker(LilyHBaseSchema.getRecordTable(hbaseTableFactory), RecordCf.DATA.bytes,
                            RecordColumn.LOCK.bytes, 10000);
            rowLogConfManager.addRowLog("WAL", new RowLogConfig(true, false, 100L, 5000L, 5000L, 120000L, 100));
            wal = new WalRowLog("WAL", LilyHBaseSchema.getRecordTable(hbaseTableFactory),
                    LilyHBaseSchema.RecordCf.ROWLOG.bytes,
                    LilyHBaseSchema.RecordColumn.WAL_PREFIX, rowLogConfManager, rowLocker, new RowLogHashShardRouter());
            RowLogShardSetup.setupShards(1, wal, hbaseTableFactory);
        }

        repository = new HBaseRepository(typeManager, idGenerator, wal, hbaseTableFactory, blobManager, rowLocker);
        repository.setRecordUpdateHooks(recordUpdateHooks);

        repositorySetup = true;
    }

    private BlobStoreAccessFactory createBlobAccess() throws Exception {
        DFSBlobStoreAccess dfsBlobStoreAccess = new DFSBlobStoreAccess(hbaseProxy.getBlobFS(), new Path("/lily/blobs"));
        BlobStoreAccess hbaseBlobStoreAccess = new HBaseBlobStoreAccess(hadoopConf);
        BlobStoreAccess inlineBlobStoreAccess = new InlineBlobStoreAccess();

        BlobStoreAccessConfig blobStoreAccessConfig = new BlobStoreAccessConfig(dfsBlobStoreAccess.getId());

        if (hbaseBlobLimit != -1) {
            blobStoreAccessConfig.setLimit(hbaseBlobStoreAccess.getId(), hbaseBlobLimit);
        }

        if (inlineBlobLimit != -1) {
            blobStoreAccessConfig.setLimit(inlineBlobStoreAccess.getId(), inlineBlobLimit);
        }

        List<BlobStoreAccess> blobStoreAccesses =
                Arrays.asList(dfsBlobStoreAccess, hbaseBlobStoreAccess, inlineBlobStoreAccess);
        SizeBasedBlobStoreAccessFactory blobStoreAccessFactory =
                new SizeBasedBlobStoreAccessFactory(blobStoreAccesses, blobStoreAccessConfig);
        return blobStoreAccessFactory;
    }

    /**
     * Set the size limits for the inline and HBase blobs, set to -1 to disable one of these
     * stores.
     */
    public void setBlobLimits(long inlineBlobLimit, long hbaseBlobLimit) {
        this.inlineBlobLimit = inlineBlobLimit;
        this.hbaseBlobLimit = hbaseBlobLimit;
    }

    public void setupRowLogConfigurationManager() throws Exception {
        rowLogConfManager = new RowLogConfigurationManagerImpl(zk);
    }

    public void setupRemoteAccess() throws Exception {
        AvroConverter serverConverter = new AvroConverter();
        serverConverter.setRepository(repository);
        lilyServer = new NettyServer(
                new LilySpecificResponder(AvroLily.class, new AvroLilyImpl(repository, null, serverConverter),
                        serverConverter), new InetSocketAddress(0));
        lilyServer.start();

        final AvroConverter remoteConverter = new AvroConverter();
        final InetSocketAddress remoteAddr = new InetSocketAddress(lilyServer.getPort());

        remoteSchemaCache = new RemoteTestSchemaCache(zk);
        remoteTypeManager = new RemoteTypeManager(remoteAddr, remoteConverter, idGenerator, zk, remoteSchemaCache);
        remoteSchemaCache.setTypeManager(remoteTypeManager);

        remoteBlobStoreAccessFactory = createBlobAccess();
        remoteBlobManager = new BlobManagerImpl(hbaseTableFactory, remoteBlobStoreAccessFactory, false);

        remoteRepository =
                new RemoteRepository(remoteAddr, remoteConverter, remoteTypeManager, idGenerator, remoteBlobManager,
                        getHadoopConf());

        remoteConverter.setRepository(repository);
        remoteTypeManager.start();
        remoteSchemaCache.start();
    }

    public void setupMessageQueue(boolean withProcessor) throws Exception {
        setupMessageQueue(withProcessor, false);
    }

    /**
     * @param withManualProcessing if true, the MQ RowLog will be wrapped to keep track of added messages to allow
     *                             triggering a manual processing, see method {@link #processMQ}. Usually you will want
     *                             either this or withProcessor, not both.
     */
    public void setupMessageQueue(boolean withProcessor, boolean withManualProcessing) throws Exception {

        rowLogConfManager.addRowLog("MQ", new RowLogConfig(false, true, 100L, 0L, 5000L, 120000L, 100));
        rowLogConfManager.addSubscription("WAL", "MQFeeder", RowLogSubscription.Type.VM, 1);

        mq = new RowLogImpl("MQ", LilyHBaseSchema.getRecordTable(hbaseTableFactory),
                LilyHBaseSchema.RecordCf.ROWLOG.bytes,
                LilyHBaseSchema.RecordColumn.MQ_PREFIX, rowLogConfManager, null, new RowLogHashShardRouter());
        RowLogShardSetup.setupShards(1, mq, hbaseTableFactory);
        if (withManualProcessing) {
            mq = new ManualProcessRowLog(mq);
        }

        RowLogMessageListenerMapping listenerClassMapping = RowLogMessageListenerMapping.INSTANCE;
        listenerClassMapping.put("MQFeeder", createMQFeeder(mq));

        waitForSubscription(wal, "MQFeeder");

        if (withProcessor) {
            mqProcessor = new RowLogProcessorImpl(mq, rowLogConfManager, getHadoopConf());
            mqProcessor.start();
        }
    }

    /**
     * Can be overridden by subclass to provide other implementation.
     */
    public RowLogMessageListener createMQFeeder(RowLog mq) {
        return new MessageQueueFeeder(mq);
    }

    /**
     * When the message queue was setup with the option for manual processing, calling this method will
     * trigger synchronous MQ processing.
     */
    public void processMQ() throws RowLogException, InterruptedException {
        ((ManualProcessRowLog) mq).processMessages();
    }

    public void stop() throws InterruptedException {
        if (mqProcessor != null)
            mqProcessor.stop();

        Closer.close(remoteSchemaCache);
        Closer.close(remoteTypeManager);
        Closer.close(remoteRepository);

        Closer.close(typeManager);
        Closer.close(repository);

        if (lilyServer != null) {
            lilyServer.close();
            lilyServer.join();
        }

        Closer.close(rowLogConfManager);

        Closer.close(zk);
        Closer.close(hbaseProxy);
        coreSetup = false;
        repositorySetup = false;
        typeManagerSetup = false;
    }

    public void waitForSubscription(RowLog rowLog, String subscriptionId) throws InterruptedException {
        boolean subscriptionKnown = false;
        int timeOut = 10000;
        long waitUntil = System.currentTimeMillis() + timeOut;
        while (!subscriptionKnown && System.currentTimeMillis() < waitUntil) {
            if (rowLog.getSubscriptionIds().contains(subscriptionId)) {
                subscriptionKnown = true;
                break;
            }
            Thread.sleep(10);
        }
        Assert.assertTrue("Subscription '" + subscriptionId + "' not known to rowlog within timeout " + timeOut + "ms",
                subscriptionKnown);
    }

    public ZooKeeperItf getZk() {
        return zk;
    }

    public TypeManager getRemoteTypeManager() {
        return remoteTypeManager;
    }

    public Repository getRepository() {
        return repository;
    }

    public Repository getRemoteRepository() {
        return remoteRepository;
    }

    /**
     * Returns a default typemanager.
     */
    public TypeManager getTypeManager() {
        return typeManager;
    }

    /**
     * Returns a new instance of a HBaseTypeManager, different than the default
     * typemanager.
     */
    public TypeManager getNewTypeManager() throws IOException, InterruptedException, KeeperException,
            RepositoryException {
        return new HBaseTypeManager(new IdGeneratorImpl(), hadoopConf, zk, hbaseTableFactory);
    }

    public IdGenerator getIdGenerator() {
        return idGenerator;
    }

    public RowLog getWal() {
        return wal;
    }

    public RowLog getMq() {
        return mq;
    }

    public Configuration getHadoopConf() {
        return hadoopConf;
    }

    public HBaseTableFactory getHbaseTableFactory() {
        return hbaseTableFactory;
    }

    public RowLogConfigurationManagerImpl getRowLogConfManager() {
        return rowLogConfManager;
    }

    public BlobStoreAccessFactory getBlobStoreAccessFactory() {
        return blobStoreAccessFactory;
    }

    public BlobStoreAccessFactory getRemoteBlobStoreAccessFactory() {
        return remoteBlobStoreAccessFactory;
    }

    public BlobManager getBlobManager() {
        return blobManager;
    }

    public BlobManager getRemoteBlobManager() {
        return remoteBlobManager;
    }

    public RowLocker getRowLocker() {
        return rowLocker;
    }

    private class RemoteTestSchemaCache extends AbstractSchemaCache implements SchemaCache {

        private TypeManager typeManager;

        public RemoteTestSchemaCache(ZooKeeperItf zooKeeper) {
            super(zooKeeper);
        }

        public void setTypeManager(TypeManager typeManager) {
            this.typeManager = typeManager;
        }

        @Override
        protected TypeManager getTypeManager() {
            return typeManager;
        }

    }
}
