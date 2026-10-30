package com.inpartnerhub.diligence.report.internal;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.notification.NotificationType;
import com.inpartnerhub.diligence.notification.Notifier;
import com.inpartnerhub.diligence.notification.Notifier.Notification;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.MemberSummary;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.report.ReportEvents.ReportChanged;

/** Tells the client when a report was issued (PRD RPT-01, ADM-05). */
@Component
class ReportNotifications {

    private final ProjectDirectory projects;
    private final Notifier notifier;

    ReportNotifications(ProjectDirectory projects, Notifier notifier) {
        this.projects = projects;
        this.notifier = notifier;
    }

    @ApplicationModuleListener
    void on(ReportChanged event) {
        if (!"issued".equals(event.change())) {
            return;
        }
        var clients = projects.members(event.projectId()).stream().filter(m -> m.role() == ProjectRole.CLIENT)
                .map(MemberSummary::userId).collect(Collectors.toSet());
        if (!clients.isEmpty()) {
            notifier.notify(new Notification(clients, NotificationType.REPORT_ISSUED, event.projectId(),
                    Map.of("projectCode", event.projectCode(), "title", event.title(),
                            "version", String.valueOf(event.versionNumber())),
                    "/projects/" + event.projectId() + "/reports"));
        }
    }
}
