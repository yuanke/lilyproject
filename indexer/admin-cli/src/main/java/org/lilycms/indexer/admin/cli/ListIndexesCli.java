package org.lilycms.indexer.admin.cli;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.joda.time.DateTime;
import org.lilycms.indexer.model.api.*;
import org.lilycms.indexer.model.impl.IndexerModelImpl;
import org.lilycms.util.zookeeper.ZooKeeperItf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ListIndexesCli extends BaseIndexerAdminCli {
    @Override
    protected String getCmdName() {
        return "lily-list-indexes";
    }

    public static void main(String[] args) {
        start(args, new ListIndexesCli());
    }

    @Override
    public List<Option> getOptions() {
        return Collections.emptyList();
    }

    public void run(ZooKeeperItf zk, CommandLine cmd) throws Exception {
        WriteableIndexerModel model = new IndexerModelImpl(zk);

        List<IndexDefinition> indexes = new ArrayList<IndexDefinition>(model.getIndexes());
        Collections.sort(indexes, IndexDefinitionNameComparator.INSTANCE);

        System.out.println("Number of indexes: " + indexes.size());
        System.out.println();

        for (IndexDefinition index : indexes) {
            System.out.println(index.getName());
            System.out.println("  + General state: " + index.getGeneralState());
            System.out.println("  + Update state: " + index.getUpdateState());
            System.out.println("  + Batch build state: " + index.getBatchBuildState());
            System.out.println("  + Queue subscription ID: " + index.getQueueSubscriptionId());
            System.out.println("  + SOLR shards: ");
            for (String shard : index.getSolrShards()) {
                System.out.println("    + " + shard);
            }

            ActiveBatchBuildInfo activeBatchBuild = index.getActiveBatchBuildInfo();
            if (activeBatchBuild != null) {
                System.out.println("  + Active batch build:");
                System.out.println("    + Hadoop Job ID: " + activeBatchBuild.getJobId());
                System.out.println("    + Submitted at: " + new DateTime(activeBatchBuild.getSubmitTime()).toString());
            }

            BatchBuildInfo lastBatchBuild = index.getLastBatchBuildInfo();
            if (lastBatchBuild != null) {
                System.out.println("  + Last batch build:");
                System.out.println("    + Hadoop Job ID: " + lastBatchBuild.getJobId());
                System.out.println("    + Submitted at: " + new DateTime(lastBatchBuild.getSubmitTime()).toString());
                System.out.println("    + Success: " + lastBatchBuild.getSuccess());
                System.out.println("    + Job state: " + lastBatchBuild.getJobState());
            }

            System.out.println();
        }
    }
}