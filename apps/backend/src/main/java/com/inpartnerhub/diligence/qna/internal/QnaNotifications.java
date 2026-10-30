package com.inpartnerhub.diligence.qna.internal;

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
import com.inpartnerhub.diligence.qna.QnaEvents.QuestionRef;
import com.inpartnerhub.diligence.qna.QnaEvents.QuestionStatusChanged;

/**
 * Q&A notifications (PRD QNA-02, ADM-05), after commit in the system context. The target only hears about questions
 * that were sent to it; a client who asked a question only hears back when the team has closed it.
 */
@Component
class QnaNotifications {

    private final ProjectDirectory projects;
    private final Notifier notifier;

    QnaNotifications(ProjectDirectory projects, Notifier notifier) {
        this.projects = projects;
        this.notifier = notifier;
    }

    @ApplicationModuleListener
    void on(QuestionStatusChanged event) {
        var members = projects.members(event.projectId());
        var question = event.question();
        var askedByClient = members.stream()
                .anyMatch(m -> m.userId().equals(question.askedBy()) && m.role() == ProjectRole.CLIENT);
        var params = new HashMap<>(params(event.projectCode(), question));
        if (event.feedback() != null) {
            params.put("feedback", event.feedback());
        }
        Set<UUID> recipients;
        NotificationType type;
        switch (event.toStatus()) {
            case "PENDING_APPROVAL" -> {
                recipients = select(members, m -> ProjectRole.MANAGERS.contains(m.role()));
                type = NotificationType.QUESTION_PENDING_APPROVAL;
            }
            case "DRAFT" -> {
                recipients = Set.of(question.askedBy());
                type = NotificationType.QUESTION_RETURNED;
            }
            case "SENT" -> {
                recipients = targets(members, question);
                type = NotificationType.QUESTION_SENT;
            }
            case "ANSWERED" -> {
                recipients = new HashSet<>(select(members, m -> ProjectRole.MANAGERS.contains(m.role())
                        || (m.role() == ProjectRole.ANALYST && m.covers(question.workstreamId()))));
                if (!askedByClient) {
                    recipients.add(question.askedBy());
                }
                type = NotificationType.QUESTION_ANSWERED;
            }
            case "CLOSED" -> {
                if (!askedByClient) {
                    return;
                }
                recipients = Set.of(question.askedBy());
                type = NotificationType.QUESTION_CLOSED;
            }
            default -> {
                return;
            }
        }
        var others = new HashSet<>(recipients);
        var actor = event.requestMetadata().actorId();
        if (actor != null) {
            others.remove(actor);
        }
        if (!others.isEmpty()) {
            notifier.notify(new Notification(others, type, event.projectId(), params,
                    "/projects/" + event.projectId() + "/qna?question=" + question.questionId()));
        }
    }

    /** The named recipient, or every target member covering the question's workstream. */
    static Set<UUID> targets(Collection<MemberSummary> members, QuestionRef question) {
        if (question.recipientUserId() != null && members.stream().anyMatch(m -> m.role() == ProjectRole.TARGET
                && m.userId().equals(question.recipientUserId()))) {
            return Set.of(question.recipientUserId());
        }
        return select(members, m -> m.role() == ProjectRole.TARGET && m.covers(question.workstreamId()));
    }

    static Map<String, String> params(String projectCode, QuestionRef question) {
        var body = question.body().replaceAll("\\s+", " ");
        return Map.of("projectCode", projectCode, "questionNumber", question.number(),
                "excerpt", body.length() <= 120 ? body : body.substring(0, 117) + "...");
    }

    private static Set<UUID> select(Collection<MemberSummary> members, Predicate<MemberSummary> filter) {
        return members.stream().filter(filter).map(MemberSummary::userId).collect(Collectors.toSet());
    }
}
