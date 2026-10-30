package com.inpartnerhub.diligence.request.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** A data-room document delivered for a request item, with an optional note (PRD REQ-03). */
@Entity
@IdClass(RequestItemDocument.Key.class)
@Table(schema = "req", name = "request_item_document")
class RequestItemDocument {

    record Key(UUID requestItemId, UUID documentId) {

        Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "request_item_id")
    private UUID requestItemId;

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    private String comment;

    @Column(name = "linked_by", nullable = false, updatable = false)
    private UUID linkedBy;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected RequestItemDocument() {
    }

    RequestItemDocument(UUID requestItemId, UUID documentId, UUID projectId, String comment, UUID linkedBy) {
        this.requestItemId = requestItemId;
        this.documentId = documentId;
        this.projectId = projectId;
        this.comment = comment;
        this.linkedBy = linkedBy;
        this.linkedAt = Instant.now();
    }

    UUID getRequestItemId() {
        return requestItemId;
    }

    UUID getDocumentId() {
        return documentId;
    }

    String getComment() {
        return comment;
    }

    UUID getLinkedBy() {
        return linkedBy;
    }

    Instant getLinkedAt() {
        return linkedAt;
    }
}
