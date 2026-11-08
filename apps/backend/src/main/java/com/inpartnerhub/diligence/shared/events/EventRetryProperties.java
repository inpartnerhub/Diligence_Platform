package com.inpartnerhub.diligence.shared.events;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retry of failed module-listener invocations (issue #81).
 *
 * @param interval     how often failed event publications are resubmitted
 * @param minAge       a publication is only resubmitted once it is at least this old, so a listener that just
 *                     failed is not hammered
 * @param maxAttempts  after this many completion attempts a publication is left FAILED for an operator
 *                     ({@code diligence_events_publications_exhausted}, runbook failed-events.md)
 * @param batchSize    publications resubmitted per run
 */
@ConfigurationProperties("diligence.events.retry")
record EventRetryProperties(Duration interval, Duration minAge, Integer maxAttempts, Integer batchSize) {

    EventRetryProperties {
        interval = interval == null || interval.isNegative() || interval.isZero() ? Duration.ofMinutes(5) : interval;
        minAge = minAge == null || minAge.isNegative() ? Duration.ofMinutes(5) : minAge;
        maxAttempts = maxAttempts == null || maxAttempts < 1 ? 24 : maxAttempts;
        batchSize = batchSize == null || batchSize < 1 ? 100 : batchSize;
    }
}
