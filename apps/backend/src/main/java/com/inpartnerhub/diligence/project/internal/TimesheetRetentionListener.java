package com.inpartnerhub.diligence.project.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectEvents.ProjectDataPurgeRequested;

/**
 * Project purge (PRD ADM-06, UU PDP, #114): deletes this module's time entries when an archived project
 * passes its retention period. Runs without a signed-in user, i.e. as the RLS system role.
 */
@Component
class TimesheetRetentionListener {

    private static final Logger log = LoggerFactory.getLogger(TimesheetRetentionListener.class);

    private final TimeEntryRepository timeEntries;

    TimesheetRetentionListener(TimeEntryRepository timeEntries) {
        this.timeEntries = timeEntries;
    }

    @ApplicationModuleListener
    void on(ProjectDataPurgeRequested event) {
        timeEntries.deleteByProjectId(event.projectId());
        log.info("Purged the time entries of project {} ({})", event.code(), event.projectId());
    }
}
