package com.inpartnerhub.diligence.qna.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.inpartnerhub.diligence.qna.QnaEvents.QuestionRef;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A question to the target (PRD QNA-01), possibly asked by the client. */
@Entity
@Table(schema = "qna", name = "question")
class Question {

    static final int MAX_BODY = 4000;

    /** What the question refers to; every link is optional. */
    record Links(UUID workstreamId, UUID requestItemId, UUID documentId, UUID findingId) {
    }

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "workstream_id")
    private UUID workstreamId;

    @Column(name = "request_item_id")
    private UUID requestItemId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "finding_id")
    private UUID findingId;

    @Column(name = "asked_by", nullable = false, updatable = false)
    private UUID askedBy;

    @Column(name = "recipient_user_id")
    private UUID recipientUserId;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionStatus status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    private String feedback;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected Question() {
    }

    Question(UUID projectId, String number, UUID askedBy, QuestionStatus initial) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.number = number;
        this.askedBy = askedBy;
        this.status = initial;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void write(String body, Links links, UUID recipientUserId, LocalDate dueDate) {
        this.body = body;
        this.workstreamId = links.workstreamId();
        this.requestItemId = links.requestItemId();
        this.documentId = links.documentId();
        this.findingId = links.findingId();
        this.recipientUserId = recipientUserId;
        this.dueDate = dueDate;
        this.updatedAt = Instant.now();
    }

    void moveTo(QuestionStatus next, UUID userId, Instant at, String feedback) {
        switch (next) {
            case SENT -> {
                if (status == QuestionStatus.PENDING_APPROVAL || status == QuestionStatus.DRAFT) {
                    approvedBy = userId;
                }
                sentAt = sentAt == null ? at : sentAt;
            }
            case ANSWERED -> answeredAt = at;
            case CLOSED -> closedAt = at;
            default -> {
                // DRAFT and PENDING_APPROVAL need no timestamps
            }
        }
        this.feedback = feedback;
        this.status = next;
        this.updatedAt = at;
    }

    QuestionRef ref() {
        return new QuestionRef(id, number, body, workstreamId, askedBy, recipientUserId);
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    String getNumber() {
        return number;
    }

    UUID getWorkstreamId() {
        return workstreamId;
    }

    UUID getRequestItemId() {
        return requestItemId;
    }

    UUID getDocumentId() {
        return documentId;
    }

    UUID getFindingId() {
        return findingId;
    }

    UUID getAskedBy() {
        return askedBy;
    }

    UUID getRecipientUserId() {
        return recipientUserId;
    }

    String getBody() {
        return body;
    }

    QuestionStatus getStatus() {
        return status;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    String getFeedback() {
        return feedback;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    UUID getApprovedBy() {
        return approvedBy;
    }

    Instant getSentAt() {
        return sentAt;
    }

    Instant getAnsweredAt() {
        return answeredAt;
    }

    Instant getClosedAt() {
        return closedAt;
    }
}
