package com.inpartnerhub.diligence.request.internal;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.notification.NotificationType;
import com.inpartnerhub.diligence.notification.Notifier;
import com.inpartnerhub.diligence.notification.Notifier.Notification;
import com.inpartnerhub.diligence.project.ProjectDirectory;

/**
 * Automatic reminders (PRD REQ-04), run once per working day by {@link RequestReminderJob}: every target recipient
 * gets one reminder per project counting their open items due within the configured days and those overdue; the
 * project's managers get a digest of overdue items. Each item is reminded at most once per day, so a re-run on the
 * same day sends nothing twice.
 */
@Service
@Transactional
class RequestReminderService {

    private static final Logger log = LoggerFactory.getLogger(RequestReminderService.class);

    private record Counts(long dueSoon, long overdue) {

        Counts add(Counts other) {
            return new Counts(dueSoon + other.dueSoon, overdue + other.overdue);
        }
    }

    private final RequestItemRepository items;
    private final ProjectDirectory projects;
    private final Notifier notifier;
    private final int daysBefore;

    RequestReminderService(RequestItemRepository items, ProjectDirectory projects, Notifier notifier,
            @Value("${diligence.request.reminder-days-before:3}") int daysBefore) {
        this.items = items;
        this.projects = projects;
        this.notifier = notifier;
        this.daysBefore = daysBefore;
    }

    /** @return the number of notifications sent */
    int remind(LocalDate today) {
        var due = items.dueForReminder(RequestStatus.OPEN, today.plusDays(daysBefore), today);
        var byProject = due.stream().collect(Collectors.groupingBy(RequestItem::getProjectId, LinkedHashMap::new,
                Collectors.toList()));
        var sent = 0;
        for (var entry : byProject.entrySet()) {
            sent += remindProject(entry.getKey(), entry.getValue(), today);
        }
        if (sent > 0) {
            log.info("Sent {} request-list reminders for {} projects", sent, byProject.size());
        }
        return sent;
    }

    private int remindProject(UUID projectId, List<RequestItem> due, LocalDate today) {
        var project = projects.summary(projectId).orElse(null);
        if (project == null || project.archived()) {
            return 0;
        }
        var members = projects.members(projectId);
        var perRecipient = new HashMap<UUID, Counts>();
        long overdue = 0;
        for (var item : due) {
            var late = item.getDueDate().isBefore(today);
            overdue += late ? 1 : 0;
            var counts = late ? new Counts(0, 1) : new Counts(1, 0);
            RequestNotifications.targetRecipients(members, item.ref())
                    .forEach(user -> perRecipient.merge(user, counts, Counts::add));
            item.reminded(today);
        }

        var sent = 0;
        var byCounts = perRecipient.entrySet().stream().collect(Collectors.groupingBy(Map.Entry::getValue,
                Collectors.mapping(Map.Entry::getKey, Collectors.toSet())));
        for (var group : byCounts.entrySet()) {
            notifier.notify(new Notification(group.getValue(), NotificationType.REQUEST_DUE_REMINDER, projectId,
                    Map.of("projectCode", project.code(), "dueSoon", String.valueOf(group.getKey().dueSoon()),
                            "overdue", String.valueOf(group.getKey().overdue())),
                    RequestNotifications.link(projectId, null)));
            sent += group.getValue().size();
        }
        var managers = RequestNotifications.managers(members);
        if (overdue > 0 && !managers.isEmpty()) {
            notifier.notify(new Notification(managers, NotificationType.REQUEST_OVERDUE_DIGEST, projectId,
                    Map.of("projectCode", project.code(), "overdue", String.valueOf(overdue)),
                    RequestNotifications.link(projectId, null)));
            sent += managers.size();
        }
        return sent;
    }
}
