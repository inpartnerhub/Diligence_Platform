package com.inpartnerhub.diligence.finding.internal;

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

import com.inpartnerhub.diligence.finding.FindingEvents.FindingEscalated;
import com.inpartnerhub.diligence.finding.FindingEvents.FindingRef;
import com.inpartnerhub.diligence.finding.FindingEvents.FindingStatusChanged;
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.notification.NotificationType;
import com.inpartnerhub.diligence.notification.Notifier;
import com.inpartnerhub.diligence.notification.Notifier.Notification;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.MemberSummary;
import com.inpartnerhub.diligence.project.ProjectRole;

/**
 * Turns the finding workflow into notifications (PRD FND-04, ADM-05) and escalations (Appendix A). Runs after commit
 * in the system context. The client only ever hears about released findings; the target never hears about findings.
 */
@Component
class FindingNotifications {

    private final ProjectDirectory projects;
    private final Notifier notifier;

    FindingNotifications(ProjectDirectory projects, Notifier notifier) {
        this.projects = projects;
        this.notifier = notifier;
    }

    @ApplicationModuleListener
    void on(FindingStatusChanged event) {
        var members = projects.members(event.projectId());
        var finding = event.finding();
        var params = new HashMap<>(params(event.projectCode(), finding));
        Set<UUID> recipients;
        NotificationType type;
        switch (event.toStatus()) {
            case "IN_REVIEW" -> {
                recipients = select(members, m -> ProjectRole.MANAGERS.contains(m.role()));
                type = NotificationType.FINDING_SUBMITTED;
            }
            case "DRAFT" -> {
                recipients = Set.of(finding.authorId());
                type = NotificationType.FINDING_RETURNED;
                params.put("reason", event.note() == null ? "-" : event.note());
            }
            case "APPROVED" -> {
                recipients = new HashSet<>(select(members, m -> m.role() == ProjectRole.PARTNER));
                recipients.add(finding.authorId());
                type = NotificationType.FINDING_APPROVED;
            }
            case "RELEASED" -> {
                recipients = select(members, m -> m.role() == ProjectRole.CLIENT);
                type = NotificationType.FINDING_RELEASED;
            }
            default -> {
                return;
            }
        }
        send(recipients, event.requestMetadata().actorId(), type, event.projectId(), params, finding);
    }

    @ApplicationModuleListener
    void on(FindingEscalated event) {
        var members = projects.members(event.projectId());
        var recipients = select(members, m -> m.role() == ProjectRole.PROJECT_MANAGER
                || (event.level() == RiskLevel.CRITICAL && m.role() == ProjectRole.PARTNER));
        var params = new HashMap<>(params(event.projectCode(), event.finding()));
        params.put("level", event.level().name());
        params.put("score", String.valueOf(event.score()));
        send(recipients, event.requestMetadata().actorId(), NotificationType.FINDING_ESCALATED, event.projectId(),
                params, event.finding());
    }

    private void send(Set<UUID> recipients, UUID actor, NotificationType type, UUID projectId,
            Map<String, String> params, FindingRef finding) {
        var others = new HashSet<>(recipients);
        if (actor != null) {
            others.remove(actor);
        }
        if (!others.isEmpty()) {
            notifier.notify(new Notification(others, type, projectId, params,
                    "/projects/" + projectId + "/findings?finding=" + finding.findingId()));
        }
    }

    static Map<String, String> params(String projectCode, FindingRef finding) {
        var title = finding.title().length() <= 120 ? finding.title() : finding.title().substring(0, 117) + "...";
        return Map.of("projectCode", projectCode, "findingNumber", finding.number(), "title", title);
    }

    private static Set<UUID> select(Collection<MemberSummary> members, Predicate<MemberSummary> filter) {
        return members.stream().filter(filter).map(MemberSummary::userId).collect(Collectors.toSet());
    }
}
