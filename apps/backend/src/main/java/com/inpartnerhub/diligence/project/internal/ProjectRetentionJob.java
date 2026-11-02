package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Scheduled job to enforce data retention policies and automatically purge expired project data (PRD ADM-06).
 * ShedLock ensures only one replica executes the cleanup run.
 */
@Component
class ProjectRetentionJob {

    private final ProjectRetentionService retentionService;

    ProjectRetentionJob(ProjectRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(cron = "${diligence.retention.cron:0 0 2 * * *}", zone = "${diligence.retention.zone:Asia/Jakarta}")
    @SchedulerLock(name = "project-retention-purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void run() {
        retentionService.purgeExpiredProjects(Instant.now());
    }
}

