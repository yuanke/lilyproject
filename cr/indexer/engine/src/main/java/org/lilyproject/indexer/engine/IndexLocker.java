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
package org.lilyproject.indexer.engine;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.Stat;
import org.lilyproject.repository.api.RecordId;
import org.lilyproject.util.zookeeper.ZkUtil;
import org.lilyproject.util.zookeeper.ZooKeeperItf;
import org.lilyproject.util.zookeeper.ZooKeeperOperation;

import java.util.Arrays;

// About the IndexLocker:
//
// To avoid multiple processes/threads concurrently indexing the same record, the convention is
// they are required to take an 'index lock' on the record.
//
// This lock is implemented using ZooKeeper. Given a single ZK quorum, this puts ultimately some
// limit on the number of locks that can be taken/released within a certain amount of time, and
// hence on the amount of records that can be indexed within that time, but we felt that at the
// moment this should be far from an issue. Also, the number of indexing processes is typically
// fairly limited.
//
// The IndexLocker does not take the common approach of having a lock path below which an ephemeral
// node is created by the party desiring to obtain the lock: this would require creating a non-ephemeral
// node for each record within ZK. Therefore, the lock is simply obtained by creating a node for
// the record within ZK. If this succeeds, you have the lock, if this fails because the node already
// exist, you have to wait a bit and retry.
//
// Update April 2011: due to a combination of changes (the RowLog now guarantees that it does not
// deliver two messages for the same row and subscription concurrently, and the IndexUpdater does not reindex
// denormalized data immediately but by pushing messages on the queue again), the index lock has
// become mostly unnecessary. There is still one case left where it is important (= where there
// can be concurrent indexing of the same record), and that is when doing a full index rebuild
// while also having incremental indexing enabled. In such case, the chance for conflicts will
// be much lower than in the case of reindexing of denormalized data, so one might prefer the
// higher performance (and less ZooKeeper stressing) obtained by disabling this index locking.
//
// Update April 2011: this locking should really be a lock per index, not a global
// index lock for each record, which would lower chances of contention when having multiple
// indexes defined. Will leave it like this though since I'd rather see the need for this locking
// removed altogether.

public class IndexLocker {
    private ZooKeeperItf zk;
    private int waitBetweenTries = 20;
    private int maxWaitTime = 20000;
    /**
     * Flag to allow globally disabling the index locking.
     */
    private boolean enabled = true;

    private Log log = LogFactory.getLog(getClass());

    private static final String LOCK_PATH = "/lily/indexer/recordlock";        

    public IndexLocker(ZooKeeperItf zk, boolean enabled) throws InterruptedException, KeeperException {
        this.zk = zk;
        this.enabled = enabled;
        ZkUtil.createPath(zk, LOCK_PATH);
    }

    public IndexLocker(ZooKeeperItf zk, int waitBetweenTries, int maxWaitTime) throws InterruptedException, KeeperException {
        this.zk = zk;
        this.waitBetweenTries = waitBetweenTries;
        this.maxWaitTime = maxWaitTime;
        ZkUtil.createPath(zk, LOCK_PATH);
    }

    /**
     * Obtain a lock for the given record. The lock is thread-based, i.e. it is re-entrant, obtaining
     * a lock for the same record twice from the same {ZK session, thread} will silently succeed.
     *
     * <p>If this method returns without failure, you obtained the lock
     *
     * @throws IndexLockTimeoutException if the lock could not be obtained within the given timeout.
     */
    public void lock(RecordId recordId) throws IndexLockException {
        if (!enabled) {
            return;
        }

        if (zk.isCurrentThreadEventThread()) {
            throw new RuntimeException("IndexLocker should not be used from within the ZooKeeper event thread.");
        }

        try {
            long startTime = System.currentTimeMillis();
            final String lockPath = getPath(recordId);

            final byte[] data = Bytes.toBytes(Thread.currentThread().getId());

            while (true) {
                if (System.currentTimeMillis() - startTime > maxWaitTime) {
                    // we have been attempting long enough to get the lock, without success
                    throw new IndexLockTimeoutException("Failed to obtain an index lock for record " + recordId +
                            " within " + maxWaitTime + " ms.");
                }

                try {
                    zk.retryOperation(new ZooKeeperOperation<Object>() {
                        @Override
                        public Object execute() throws KeeperException, InterruptedException {
                            zk.create(lockPath, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                            return null;
                        }
                    });
                    // We successfully created the node, hence we have the lock.
                    return;
                } catch (KeeperException.NodeExistsException e) {
                    // ignore, see next
                }

                // In case creating the node failed, it does not mean we do not have the lock: in case
                // of connection loss, we might not know if we actually succeeded creating the node, therefore
                // read the owner and thread id to check.
                boolean hasLock = zk.retryOperation(new ZooKeeperOperation<Boolean>() {
                    @Override
                    public Boolean execute() throws KeeperException, InterruptedException {
                        try {
                            Stat stat = new Stat();
                            byte[] currentData = zk.getData(lockPath, false, stat);
                            return (stat.getEphemeralOwner() == zk.getSessionId() && Arrays.equals(currentData, data));
                        } catch (KeeperException.NoNodeException e) {
                            return false;
                        }
                    }
                });

                if (hasLock) {
                    return;
                }

                Thread.sleep(waitBetweenTries);
            }
        } catch (Throwable throwable) {
            if (throwable instanceof IndexLockException)
                throw (IndexLockException)throwable;
            throw new IndexLockException("Error taking index lock on record " + recordId, throwable);
        }
    }

    public void unlock(final RecordId recordId) throws IndexLockException, InterruptedException,
            KeeperException {

        if (!enabled) {
            return;
        }

        if (zk.isCurrentThreadEventThread()) {
            throw new RuntimeException("IndexLocker should not be used from within the ZooKeeper event thread.");
        }

        final String lockPath = getPath(recordId);

        // The below loop is because, even if our thread is interrupted, we still want to remove the lock.
        // The interruption might be because just one IndexUpdater is being shut down, rather than the
        // complete application, and hence session expiration will then not remove the lock.
        boolean tokenOk;
        boolean interrupted = false;
        while (true) {
            try {
                tokenOk = zk.retryOperation(new ZooKeeperOperation<Boolean>() {
                    @Override
                    public Boolean execute() throws KeeperException, InterruptedException {
                        Stat stat = new Stat();
                        byte[] data = zk.getData(lockPath, false, stat);

                        if (stat.getEphemeralOwner() == zk.getSessionId() && Bytes.toLong(data) == Thread.currentThread().getId()) {
                            zk.delete(lockPath, -1);
                            return true;
                        } else {
                            return false;
                        }
                    }
                });
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        if (!tokenOk) {
            throw new IndexLockException("You cannot remove the index lock for record " + recordId +
                    " because the token is incorrect.");
        }
    }

    public void unlockLogFailure(final RecordId recordId) {
        if (!enabled) {
            return;
        }

        try {
            unlock(recordId);
        } catch (Throwable t) {
            log.error("Error releasing lock on record " + recordId, t);
        }
    }

    public boolean hasLock(final RecordId recordId) throws IndexLockException, InterruptedException,
            KeeperException {

        if (!enabled) {
            return true;
        }

        if (zk.isCurrentThreadEventThread()) {
            throw new RuntimeException("IndexLocker should not be used from within the ZooKeeper event thread.");
        }

        final String lockPath = getPath(recordId);

        return zk.retryOperation(new ZooKeeperOperation<Boolean>() {
            @Override
            public Boolean execute() throws KeeperException, InterruptedException {
                try {
                    Stat stat = new Stat();
                    byte[] data = zk.getData(lockPath, false, stat);
                    return stat.getEphemeralOwner() == zk.getSessionId() &&
                            Bytes.toLong(data) == Thread.currentThread().getId();
                } catch (KeeperException.NoNodeException e) {
                    return false;
                }

            }
        });
    }

    private String getPath(RecordId recordId) {
        return LOCK_PATH + "/" + recordId.toString();
    }

}
