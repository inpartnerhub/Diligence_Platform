package com.inpartnerhub.diligence.request.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.notification.NotificationType;
import com.inpartnerhub.diligence.notification.Notifier;
import com.inpartnerhub.diligence.notification.Notifier.Notification;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.MemberSummary;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.request.RequestEvents.ItemRef;
import com.inpartnerhub.diligence.request.RequestEvents.RequestCommentAdded;
import com.inpartnerhub.diligence.request.RequestEvents.RequestItemsReleased;
import com.inpartnerhub.diligence.request.RequestEvents.RequestStatusChanged;

/**
 * Turns request-list events into notifications (PRD REQ-04, ADM-05). Runs after commit without a user (system
 * context), so the whole project roster is visible for choosing recipients:
 * <ul>
 * <li>to the target: released items, closed or rejected items, comments from the team (never internal notes);</li>
 * <li>to the team (managers and the workstream's analysts): deliveries and comments from the target.</li>
 * </ul>
 * Target recipients are the item's assignee, or every target member covering the item's workstream.
 */
@Component
class RequestNotifications {

    private final ProjectDirectory projects;
    private final Notifier notifier;

    RequestNotifications(ProjectDirectory projects, Notifier notifier) {
        this.projects = projects;
        this.notifier = notifier;
    }

    @ApplicationModuleListener
    void on(RequestItemsReleased event) {
        var members = projects.members(event.projectId());
        // One notification per recipient, counting the items that concern them.
        var counts = new HashMap<UUID, Integer>();
        for (var item : event.items()) {
            targetRecipients(members, item).forEach(user -> counts.merge(user, 1, Integer::sum));
        }
        var byCount = counts.entrySet().stream().collect(Collectors.groupingBy(Map.Entry::getValue,
                Collectors.mapping(Map.Entry::getKey, Collectors.toSet())));
        byCount.forEach((count, recipients) -> notifier.notify(new Notification(recipients,
                NotificationType.REQUEST_RELEASED, event.projectId(),
                Map.of("projectCode", event.projectCode(), "count", String.valueOf(count),
                        "wave", String.valueOf(event.wave())),
                link(event.projectId(), null))));
    }

    @ApplicationModuleListener
    void on(RequestStatusChanged event) {
        var members = projects.members(event.projectId());
        Set<UUID> recipients;
        NotificationType type;
        if (event.byTarget()) {
            recipients = teamRecipients(members, event.item());
            type = NotificationType.REQUEST_SUBMITTED;
        } else if ("CLOSED".equals(event.toStatus()) || "REJECTED".equals(event.toStatus())) {
            recipients = targetRecipients(members, event.item());
            type = NotificationType.REQUEST_REVIEWED;
        } else {
            return;
        }
        var params = new HashMap<>(itemParams(event.projectCode(), event.item()));
        params.put("status", event.toStatus());
        if (event.reason() != null) {
            params.put("reason", event.reason());
        }
        send(recipients, event.requestMetadata().actorId(), type, event.projectId(), params, event.item());
    }

    @ApplicationModuleListener
    void on(RequestCommentAdded event) {
        if (event.internal()) {
            return;
        }
        var members = projects.members(event.projectId());
        var recipients = event.byTarget() ? teamRecipients(members, event.item())
                : targetRecipients(members, event.item());
        send(recipients, event.authorId(), NotificationType.REQUEST_COMMENTED, event.projectId(),
                itemParams(event.projectCode(), event.item()), event.item());
    }

    private void send(Set<UUID> recipients, UUID actor, NotificationType type, UUID projectId,
            Map<String, String> params, ItemRef item) {
        var others = new HashSet<>(recipients);
        if (actor != null) {
            others.remove(actor);
        }
        if (!others.isEmpty()) {
            notifier.notify(new Notification(others, type, projectId, params, link(projectId, item.itemId())));
        }
    }

    static Set<UUID> targetRecipients(Collection<MemberSummary> members, ItemRef item) {
        if (item.assigneeUserId() != null && members.stream().anyMatch(m -> m.role() == ProjectRole.TARGET
                && m.userId().equals(item.assigneeUserId()))) {
            return Set.of(item.assigneeUserId());
        }
        return select(members, m -> m.role() == ProjectRole.TARGET && m.covers(item.workstreamId()));
    }

    static Set<UUID> teamRecipients(Collection<MemberSummary> members, ItemRef item) {
        return select(members, m -> ProjectRole.MANAGERS.contains(m.role())
                || (m.role() == ProjectRole.ANALYST && m.covers(item.workstreamId())));
    }

    static Set<UUID> managers(Collection<MemberSummary> members) {
        return select(members, m -> ProjectRole.MANAGERS.contains(m.role()));
    }

    static Map<String, String> itemParams(String projectCode, ItemRef item) {
        return Map.of("projectCode", projectCode, "itemNumber", item.number(),
                "description", shorten(item.description()));
    }

    static String link(UUID projectId, UUID itemId) {
        return "/projects/" + projectId + "/requests" + (itemId == null ? "" : "?item=" + itemId);
    }

    private static Set<UUID> select(Collection<MemberSummary> members, Predicate<MemberSummary> filter) {
        return members.stream().filter(filter).map(MemberSummary::userId).collect(Collectors.toSet());
    }

    private static String shorten(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }
}
