package org.lilycms.testfw;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hdfs.MiniDFSCluster;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Utility to easily launch a full HBase with a temporary storage. Intended to be used to run testcases
 * against (see HBaseProxy connect mode).
 *
 * <p>Disclaimer: much of the code was copied from HBase's HBaseTestingUtility, and slightly adjusted to be able
 * to fix ZK and HDFS port numbers.
 */
public class HBaseRunner {
    private MiniZooKeeperCluster zkCluster = null;
    private MiniDFSCluster dfsCluster = null;
    private MiniHBaseCluster hbaseCluster = null;
    private File clusterTestBuildDir = null;
    private Configuration conf;

    public static final String TEST_DIRECTORY_KEY = "test.build.data";
    public static final String DEFAULT_TEST_DIRECTORY = "target/build/data";

    public static void main(String[] args) throws Exception {
        new HBaseRunner().run();
    }

    public void run() throws Exception {
        conf = HBaseConfiguration.create();

        // The following properties are from HBase's src/test/resources/hbase-site.xml
        conf.set("hbase.regionserver.msginterval", "1000");
        conf.set("hbase.client.pause", "5000");
        conf.set("hbase.client.retries.number", "4");
        conf.set("hbase.master.meta.thread.rescanfrequency", "10000");
        conf.set("hbase.server.thread.wakefrequency", "1000");
        conf.set("hbase.regionserver.handler.count", "5");
        conf.set("hbase.master.info.port", "-1");
        conf.set("hbase.regionserver.info.port", "-1");
        conf.set("hbase.regionserver.info.port.auto", "true");
        conf.set("hbase.master.lease.thread.wakefrequency", "3000");
        conf.set("hbase.regionserver.optionalcacheflushinterval", "1000");
        conf.set("hbase.regionserver.safemode", "false");

        startMiniCluster(1);
    }

    public MiniHBaseCluster startMiniCluster(final int servers)
            throws Exception {
        // Make a new random dir to home everything in.  Set it as system property.
        // minidfs reads home from system property.
        this.clusterTestBuildDir = setupClusterTestBuildDir();
        System.setProperty(TEST_DIRECTORY_KEY, this.clusterTestBuildDir.getPath());
        // Bring up mini dfs cluster. This spews a bunch of warnings about missing
        // scheme. Complaints are 'Scheme is undefined for build/test/data/dfs/name1'.
        startMiniDFSCluster(servers, this.clusterTestBuildDir);

        // Mangle conf so fs parameter points to minidfs we just started up
        FileSystem fs = this.dfsCluster.getFileSystem();
        this.conf.set("fs.defaultFS", fs.getUri().toString());
        // Do old style too just to be safe.
        this.conf.set("fs.default.name", fs.getUri().toString());
        this.dfsCluster.waitClusterUp();

        // Start up a zk cluster.
        if (this.zkCluster == null) {
            startMiniZKCluster(this.clusterTestBuildDir);
        }

        // Now do the mini hbase cluster.  Set the hbase.rootdir in config.
        Path hbaseRootdir = fs.makeQualified(fs.getHomeDirectory());
        this.conf.set(HConstants.HBASE_DIR, hbaseRootdir.toString());
        fs.mkdirs(hbaseRootdir);
        FSUtils.setVersion(fs, hbaseRootdir);
        this.hbaseCluster = new MiniHBaseCluster(this.conf, servers);
        // Don't leave here till we've done a successful scan of the .META.
        HTable t = new HTable(this.conf, HConstants.META_TABLE_NAME);
        ResultScanner s = t.getScanner(new Scan());
        while (s.next() != null) continue;
        System.out.println("-------------------------");
        System.out.println("Minicluster is up");
        System.out.println("-------------------------");
        return this.hbaseCluster;
    }

    private static File setupClusterTestBuildDir() {
        String randomStr = UUID.randomUUID().toString();
        String dirStr = getTestDir(randomStr).toString();
        File dir = new File(dirStr).getAbsoluteFile();
        // Have it cleaned up on exit
        dir.deleteOnExit();
        return dir;
    }

    public static Path getTestDir(final String subdirName) {
        return new Path(getTestDir(), subdirName);
    }

    public static Path getTestDir() {
        return new Path(System.getProperty(TEST_DIRECTORY_KEY, DEFAULT_TEST_DIRECTORY));
    }

    private MiniZooKeeperCluster startMiniZKCluster(final File dir)
            throws Exception {
        if (this.zkCluster != null) {
            throw new IOException("Cluster already running at " + dir);
        }
        this.zkCluster = new MiniZooKeeperCluster();
        zkCluster.setClientPort(21812);
        int clientPort = this.zkCluster.startup(dir);
        this.conf.set("hbase.zookeeper.property.clientPort", Integer.toString(clientPort));
        return this.zkCluster;
    }

    public MiniDFSCluster startMiniDFSCluster(int servers, final File dir)
            throws Exception {
        // This does the following to home the minidfscluster
        //     base_dir = new File(System.getProperty("test.build.data", "build/test/data"), "dfs/");
        // Some tests also do this:
        //  System.getProperty("test.cache.data", "build/test/cache");
        if (dir == null) this.clusterTestBuildDir = setupClusterTestBuildDir();
        else this.clusterTestBuildDir = dir;
        System.setProperty(TEST_DIRECTORY_KEY, this.clusterTestBuildDir.toString());
        System.setProperty("test.cache.data", this.clusterTestBuildDir.toString());
        this.dfsCluster = new MiniDFSCluster(9000, this.conf, servers, true, true,
                true, null, null, null, null);
        return this.dfsCluster;
    }
}