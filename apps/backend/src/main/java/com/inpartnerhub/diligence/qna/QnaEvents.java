package com.inpartnerhub.diligence.qna;

import java.util.Map;
import java.util.UUID;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Audited Q&A events (PRD ADM-03); they also drive notifications (QNA-02). */
public final class QnaEvents {

    private QnaEvents() {
    }

    public record QuestionRef(UUID questionId, String number, String body, UUID workstreamId, UUID askedBy,
            UUID recipientUserId) {
    }

    public record QuestionChanged(UUID projectId, QuestionRef question, String change,
            RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "question." + change;
        }

        @Override
        public String auditObjectType() {
            return "question";
        }

        @Override
        public String auditObjectId() {
            return question.questionId().toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString(), "number", question.number());
        }
    }

    /** @param feedback  reason for returning, or what the target should add on a follow-up; {@code null} otherwise */
    public record QuestionStatusChanged(UUID projectId, String projectCode, QuestionRef question, String fromStatus,
            String toStatus, String feedback, RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "question.status-changed";
        }

        @Override
        public String auditObjectType() {
            return "question";
        }

        @Override
        public String auditObjectId() {
            return question.questionId().toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString(), "number", question.number(), "from",
                    fromStatus == null ? "-" : fromStatus, "to", toStatus);
        }
    }

    /** Export of the Q&A log (QNA-03). */
    public record QnaExported(UUID projectId, String format, int count, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "question.exported";
        }

        @Override
        public String auditObjectType() {
            return "project";
        }

        @Override
        public String auditObjectId() {
            return projectId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("format", format, "count", String.valueOf(count));
        }
    }
}
