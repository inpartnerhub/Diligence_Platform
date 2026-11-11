package com.inpartnerhub.diligence.request.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectEvents.ProjectDataPurgeRequested;

/**
 * Project purge (PRD ADM-06, UU PDP, #114): deletes this module's request items (with comments, linked documents and status history) when an archived project
 * passes its retention period. Runs without a signed-in user, i.e. as the RLS system role.
 */
@Component
class RequestRetentionListener {

    private static final Logger log = LoggerFactory.getLogger(RequestRetentionListener.class);

    private final RequestItemRepository items;

    RequestRetentionListener(RequestItemRepository items) {
        this.items = items;
    }

    @ApplicationModuleListener
    void on(ProjectDataPurgeRequested event) {
        int deleted = items.deleteAllForProject(event.projectId());
        log.info("Purged {} request items of project {} ({})", deleted, event.code(), event.projectId());
    }
}
