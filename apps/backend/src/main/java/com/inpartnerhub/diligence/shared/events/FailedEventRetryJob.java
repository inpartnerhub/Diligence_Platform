package com.inpartnerhub.diligence.shared.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Retries module listeners whose invocation failed (issue #81). A listener that throws — the search indexer while
 * OpenSearch is down, a notification while the mail server is unreachable — leaves its event publication FAILED in
 * {@code public.event_publication}. Before this job such publications were only retried when the backend restarted.
 * <p>
 * Every {@code interval} one replica (ShedLock) resubmits FAILED publications older than {@code min-age} that have
 * had fewer than {@code max-attempts}. Listeners are idempotent per event (they re-read current state), so a retry is
 * safe. Publications stuck in PUBLISHED, PROCESSING or RESUBMITTED (a replica died mid-way) are turned FAILED by
 * Modulith's staleness monitor ({@code spring.modulith.events.staleness.*}) and retried here as well.
 */
@Component
class FailedEventRetryJob {

    private static final Logger log = LoggerFactory.getLogger(FailedEventRetryJob.class);

    private final FailedEventPublications failed;
    private final EventRetryProperties properties;

    FailedEventRetryJob(FailedEventPublications failed, EventRetryProperties properties) {
        this.failed = failed;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${diligence.events.retry.interval:PT5M}",
            initialDelayString = "${diligence.events.retry.interval:PT5M}")
    @SchedulerLock(name = "failed-event-retry", lockAtMostFor = "PT10M")
    void run() {
        var maxAttempts = properties.maxAttempts();
        log.debug("Resubmitting failed event publications (min age {}, under {} attempts)", properties.minAge(),
                maxAttempts);
        failed.resubmit(ResubmissionOptions.defaults()
                .withMinAge(properties.minAge())
                .withBatchSize(properties.batchSize())
                .withMaxInFlight(properties.batchSize())
                .withFilter(publication -> publication.getCompletionAttempts() < maxAttempts));
    }
}
