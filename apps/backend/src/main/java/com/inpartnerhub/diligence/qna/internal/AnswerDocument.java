package com.inpartnerhub.diligence.qna.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** A data-room document supporting an answer. */
@Entity
@IdClass(AnswerDocument.Key.class)
@Table(schema = "qna", name = "answer_document")
class AnswerDocument {

    record Key(UUID questionId, UUID documentId) {

        Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "question_id")
    private UUID questionId;

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "linked_by", nullable = false, updatable = false)
    private UUID linkedBy;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected AnswerDocument() {
    }

    AnswerDocument(Question question, UUID documentId, UUID linkedBy) {
        this.questionId = question.getId();
        this.documentId = documentId;
        this.projectId = question.getProjectId();
        this.linkedBy = linkedBy;
        this.linkedAt = Instant.now();
    }

    UUID getDocumentId() {
        return documentId;
    }

    UUID getLinkedBy() {
        return linkedBy;
    }

    Instant getLinkedAt() {
        return linkedAt;
    }
}
