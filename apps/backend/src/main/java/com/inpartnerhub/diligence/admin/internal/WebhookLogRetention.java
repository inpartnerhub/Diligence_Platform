package com.inpartnerhub.diligence.admin.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Retention of webhook delivery logs (#90). Each log keeps the event payload, so it is project data: it is deleted
 * after {@code diligence.webhooks.log-retention}, and at once when the project it mentions is purged (ADM-06). Runs
 * without a signed-in user, i.e. as the RLS system role.
 */
@Component
class WebhookLogRetention {

    static final String PROJECT_PURGED = "project.data-purged";

    private static final Logger log = LoggerFactory.getLogger(WebhookLogRetention.class);

    private final WebhookDeliveryLogRepository logs;
    private final WebhookProperties properties;
    private final Clock clock;

    @Autowired
    WebhookLogRetention(WebhookDeliveryLogRepository logs, WebhookProperties properties) {
        this(logs, properties, Clock.systemUTC());
    }

    WebhookLogRetention(WebhookDeliveryLogRepository logs, WebhookProperties properties, Clock clock) {
        this.logs = logs;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${diligence.webhooks.log-retention-cron:0 30 2 * * *}")
    @SchedulerLock(name = "webhook-log-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void deleteExpired() {
        Instant cutoff = Instant.now(clock).minus(properties.logRetention());
        int deleted = logs.deleteDeliveredBefore(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} webhook delivery logs older than {}", deleted, cutoff);
        }
    }

    @Transactional
    public void deleteForPurgedProject(UUID projectId) {
        int deleted = logs.deleteMentioning(projectId.toString());
        log.info("Deleted {} webhook delivery logs of purged project {}", deleted, projectId);
    }
}
