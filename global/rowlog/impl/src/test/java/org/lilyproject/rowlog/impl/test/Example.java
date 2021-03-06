/*
 * Copyright 2010 Outerthought bvba
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
package org.lilyproject.rowlog.impl.test;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.lilyproject.rowlog.api.RowLog;
import org.lilyproject.rowlog.api.RowLogConfig;
import org.lilyproject.rowlog.api.RowLogException;
import org.lilyproject.rowlog.api.RowLogMessage;
import org.lilyproject.rowlog.api.RowLogMessageListener;
import org.lilyproject.rowlog.api.RowLogMessageListenerMapping;
import org.lilyproject.rowlog.api.RowLogProcessor;
import org.lilyproject.rowlog.api.RowLogSubscription.Type;
import org.lilyproject.rowlog.impl.RowLogConfigurationManagerImpl;
import org.lilyproject.rowlog.impl.RowLogHashShardRouter;
import org.lilyproject.rowlog.impl.RowLogImpl;
import org.lilyproject.rowlog.impl.RowLogProcessorImpl;
import org.lilyproject.rowlog.impl.RowLogShardSetup;
import org.lilyproject.util.hbase.HBaseTableFactoryImpl;
import org.lilyproject.util.zookeeper.ZkUtil;
import org.lilyproject.util.zookeeper.ZooKeeperItf;

public class Example {
    private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();

    public static void main(String[] args) throws Exception {
        TEST_UTIL.startMiniCluster(1);
        Configuration configuration = TEST_UTIL.getConfiguration();
        // Create the row table
        final String ROW_TABLE = "rowTable";
        final byte[] DATA_COLUMN_FAMILY = Bytes.toBytes("DataCF");
        final byte[] ROWLOG_COLUMN_FAMILY = Bytes.toBytes("RowLogCF");

        HBaseAdmin admin = null;
        HTable rowTable = null;
        try {
            admin = new HBaseAdmin(configuration);
            HTableDescriptor tableDescriptor = new HTableDescriptor(ROW_TABLE);
            tableDescriptor.addFamily(new HColumnDescriptor(DATA_COLUMN_FAMILY));
            tableDescriptor.addFamily(new HColumnDescriptor(ROWLOG_COLUMN_FAMILY));
            admin.createTable(tableDescriptor);
            rowTable = new HTable(configuration, ROW_TABLE);
        } finally {
            if (admin != null) admin.close();
        }

        // Setup a zooKeeper connection
        String zkConnectionString = configuration.get("hbase.zookeeper.quorum") + ":" + configuration.get("hbase.zookeeper.property.clientPort");
        ZooKeeperItf zooKeeper = ZkUtil.connect(zkConnectionString, 10000);

        // Create the row log configuration manager
        RowLogConfigurationManagerImpl configurationManager = new RowLogConfigurationManagerImpl(zooKeeper);

        // Create a RowLog instance
        configurationManager.addRowLog("Example", new RowLogConfig(false, true, 100L, 0L, 5000L, 120000L, 100));
        RowLog rowLog = new RowLogImpl("Example", rowTable, ROWLOG_COLUMN_FAMILY, (byte)1, configurationManager, null,
                new RowLogHashShardRouter());
        RowLogShardSetup.setupShards(1, rowLog, new HBaseTableFactoryImpl(configuration));

        // Register a listener class on the RowLogMessageListenerMapping
        RowLogMessageListenerMapping.INSTANCE.put("FooBar", new FooBarListener());

        // Add a subscription and listener to the configuration manager for the example Rowlog
        configurationManager.addSubscription("Example", "FooBar", Type.VM, 0);
        configurationManager.addListener("Example", "FooBar", "listener1");

        // The WAL use case

        // Update a row with some user data
        // and put a message on the RowLog using the same put action
        byte[] row1 = Bytes.toBytes("row1");
        Put put = new Put(row1);
        put.add(DATA_COLUMN_FAMILY, Bytes.toBytes("AUserField"), Bytes.toBytes("SomeUserData"));
        RowLogMessage message = rowLog.putMessage(row1, Bytes.toBytes("SomeInfo"), Bytes.toBytes("Updated:AUserField"), put);
        rowTable.put(put);
        // Explicitly request the RowLog to process the message
        rowLog.processMessage(message, null);

        // The MQ use case

        // Create a processor and start it
        RowLogProcessor processor = new RowLogProcessorImpl(rowLog, configurationManager, configuration);
        processor.start();

        message  = rowLog.putMessage(row1, Bytes.toBytes("SomeMoreInfo"), Bytes.toBytes("Re-evaluate:AUserField"), null);

        // Give the processor some time to process the message
        Thread.sleep(10000);
        processor.stop();
        configurationManager.shutdown();
        zooKeeper.close();
        TEST_UTIL.shutdownMiniCluster();
    }

    private static class FooBarListener implements RowLogMessageListener {
        @Override
        public boolean processMessage(RowLogMessage message) {
                System.out.println("= Received a message =");
                System.out.println(Bytes.toString(message.getRowKey()));
                System.out.println(Bytes.toString(message.getData()));
            try {
                System.out.println(Bytes.toString(message.getPayload()));
            } catch (RowLogException e) {
                // ignore
            }
            return true;
        }
    }

}
