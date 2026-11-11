package com.inpartnerhub.diligence.finding.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectEvents.ProjectDataPurgeRequested;

/**
 * Project purge (PRD ADM-06, UU PDP, #114): deletes this module's findings (with comments, sources, linked questions and status history) when an archived project
 * passes its retention period. Runs without a signed-in user, i.e. as the RLS system role.
 */
@Component
class FindingRetentionListener {

    private static final Logger log = LoggerFactory.getLogger(FindingRetentionListener.class);

    private final FindingRepository findings;

    FindingRetentionListener(FindingRepository findings) {
        this.findings = findings;
    }

    @ApplicationModuleListener
    void on(ProjectDataPurgeRequested event) {
        int deleted = findings.deleteAllForProject(event.projectId());
        log.info("Purged {} findings of project {} ({})", deleted, event.code(), event.projectId());
    }
}
