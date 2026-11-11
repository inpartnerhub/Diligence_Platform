package com.inpartnerhub.diligence.report.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectEvents.ProjectDataPurgeRequested;

/**
 * Project purge (PRD ADM-06, UU PDP, #114): deletes this module's reports (with their findings and status history; the files are deleted with the data room objects) when an archived project
 * passes its retention period. Runs without a signed-in user, i.e. as the RLS system role.
 */
@Component
class ReportRetentionListener {

    private static final Logger log = LoggerFactory.getLogger(ReportRetentionListener.class);

    private final ReportRepository reports;

    ReportRetentionListener(ReportRepository reports) {
        this.reports = reports;
    }

    @ApplicationModuleListener
    void on(ProjectDataPurgeRequested event) {
        int deleted = reports.deleteAllForProject(event.projectId());
        log.info("Purged {} reports of project {} ({})", deleted, event.code(), event.projectId());
    }
}
