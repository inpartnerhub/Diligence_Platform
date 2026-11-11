package com.inpartnerhub.diligence.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectEvents.ProjectDataPurgeRequested;

/**
 * Project purge (PRD ADM-06, UU PDP, #114): deletes this module's in-app notifications when an archived project
 * passes its retention period. Runs without a signed-in user, i.e. as the RLS system role.
 */
@Component
class NotificationRetentionListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetentionListener.class);

    private final InAppNotificationRepository notifications;

    NotificationRetentionListener(InAppNotificationRepository notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    void on(ProjectDataPurgeRequested event) {
        int deleted = notifications.deleteAllForProject(event.projectId());
        log.info("Purged {} in-app notifications of project {} ({})", deleted, event.code(), event.projectId());
    }
}
