package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identifies archived projects whose data retention period has elapsed and triggers permanent data purge (PRD ADM-06).
 */
@Service
@Transactional
class ProjectRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectRetentionService.class);

    private final ProjectRepository projects;
    private final ProjectService projectService;

    ProjectRetentionService(ProjectRepository projects, ProjectService projectService) {
        this.projects = projects;
        this.projectService = projectService;
    }

    public int purgeExpiredProjects(Instant now) {
        var candidates = projects.findArchivedCandidates();
        int purged = 0;
        for (var project : candidates) {
            if (project.getArchivedAt() == null || project.isPurged()) {
                continue;
            }
            var deadline = project.getArchivedAt().plus(project.getRetentionPeriodDays(), ChronoUnit.DAYS);
            if (!now.isBefore(deadline)) {
                log.info("Project {} ({}) passed retention deadline ({}); initiating automated purge",
                        project.getCode(), project.getId(), deadline);
                projectService.purgeExpired(project, now);
                purged++;
            }
        }
        return purged;
    }
}

