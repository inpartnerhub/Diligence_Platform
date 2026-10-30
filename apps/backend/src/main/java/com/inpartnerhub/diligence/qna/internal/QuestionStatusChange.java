package com.inpartnerhub.diligence.qna.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One step of a question's history; read by the internal team only. */
@Entity
@Table(schema = "qna", name = "question_status_history")
class QuestionStatusChange {

    @Id
    private UUID id;

    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private QuestionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private QuestionStatus toStatus;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(updatable = false)
    private String note;

    protected QuestionStatusChange() {
    }

    QuestionStatusChange(Question question, QuestionStatus fromStatus, UUID changedBy, Instant at, String note) {
        this.id = UUID.randomUUID();
        this.questionId = question.getId();
        this.projectId = question.getProjectId();
        this.fromStatus = fromStatus;
        this.toStatus = question.getStatus();
        this.changedBy = changedBy;
        this.changedAt = at;
        this.note = note;
    }

    QuestionStatus getFromStatus() {
        return fromStatus;
    }

    QuestionStatus getToStatus() {
        return toStatus;
    }

    UUID getChangedBy() {
        return changedBy;
    }

    Instant getChangedAt() {
        return changedAt;
    }

    String getNote() {
        return note;
    }
}
