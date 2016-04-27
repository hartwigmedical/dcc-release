package org.icgc.dcc.release.job.index.core;

import static org.icgc.dcc.common.core.util.stream.Collectors.toImmutableList;
import static org.icgc.dcc.release.core.document.DocumentType.DONOR_CENTRIC_TYPE;
import static org.icgc.dcc.release.job.index.factory.TransportClientFactory.newTransportClient;

import java.util.Collection;

import lombok.Cleanup;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

import org.icgc.dcc.common.core.util.stream.Streams;
import org.icgc.dcc.release.core.document.DocumentType;
import org.icgc.dcc.release.core.job.GenericJob;
import org.icgc.dcc.release.core.job.JobContext;
import org.icgc.dcc.release.core.job.JobType;
import org.icgc.dcc.release.core.task.Task;
import org.icgc.dcc.release.job.index.config.IndexProperties;
import org.icgc.dcc.release.job.index.service.IndexService;
import org.icgc.dcc.release.job.index.task.IndexTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor = @__({ @Autowired }))
public class IndexJob extends GenericJob {

  /**
   * Dependencies.
   */
  @NonNull
  private final IndexProperties properties;

  static String resolveIndexName(String releaseName) {
    return releaseName.toLowerCase();
  }

  @Override
  public JobType getType() {
    return JobType.INDEX;
  }

  @Override
  public void execute(JobContext jobContext) {
    // TODO: Fix this to be tied to a run id:
    val indexName = resolveIndexName(jobContext.getReleaseName());

    //
    // TODO: Need to use spark.dynamicAllocation.enabled to dynamically
    // increase memory for this job
    //
    // -
    // http://spark.apache.org/docs/1.2.0/job-scheduling.html#dynamic-resource-allocation
    // - https://issues.apache.org/jira/browse/SPARK-4751
    //

    @Cleanup
    val client = newTransportClient(properties.getEsUri());
    @Cleanup
    val indexService = new IndexService(client);

    // Prepare
    log.info("Initializing index...");
    indexService.initializeIndex(indexName);

    // Populate
    log.info("Populating index...");
    index(jobContext, indexName);

    // Report
    log.info("Reporting index...");
    indexService.reportIndex(indexName);

    // Compact
    log.info("Optimizing index...");
    indexService.optimizeIndex(indexName);

    // Freeze
    log.info("Freezing index...");
    indexService.freezeIndex(indexName);
  }

  private void index(JobContext jobContext, String indexName) {
    // Index donor-centric first which frequently breaks Elasticsearch
    jobContext.execute(createIndexTask(indexName, DONOR_CENTRIC_TYPE));
    jobContext.execute(createStreamingTasks(indexName));
  }

  @SneakyThrows
  private Collection<? extends Task> createStreamingTasks(final String indexName) {
    return Streams.stream(DocumentType.values())
        .filter(dt -> dt != DONOR_CENTRIC_TYPE)
        .map(dt -> createIndexTask(indexName, dt))
        .collect(toImmutableList());
  }

  private IndexTask createIndexTask(String indexName, DocumentType documentType) {
    val esUri = properties.getEsUri();

    return new IndexTask(esUri, indexName, documentType);
  }

}
