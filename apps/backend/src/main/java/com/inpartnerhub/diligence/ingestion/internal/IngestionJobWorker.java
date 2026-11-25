package com.inpartnerhub.diligence.ingestion.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls {@link IngestionJobQueue} and runs {@link IngestionPipeline} for each claimed job. Disabled on API-only pods
 * via {@code diligence.ingestion.listener-enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "diligence.ingestion.listener-enabled", havingValue = "true", matchIfMissing = true)
class IngestionJobWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobWorker.class);

    private final IngestionJobQueue queue;
    private final IngestionPipeline pipeline;

    IngestionJobWorker(IngestionJobQueue queue, IngestionPipeline pipeline) {
        this.queue = queue;
        this.pipeline = pipeline;
    }

    @Scheduled(fixedDelayString = "${diligence.ingestion.poll-interval:2s}")
    void poll() {
        while (!Thread.currentThread().isInterrupted()) {
            var claimed = queue.claimNext();
            if (claimed.isEmpty()) {
                return;
            }
            var job = claimed.get();
            try {
                pipeline.onDocumentUploaded(job.event());
                queue.complete(job.id());
            } catch (RuntimeException ex) {
                log.error("Unexpected failure processing ingestion job {} for version {}",
                        job.id(), job.event().versionId(), ex);
                queue.fail(job.id(), ex.getMessage());
            }
        }
    }
}
