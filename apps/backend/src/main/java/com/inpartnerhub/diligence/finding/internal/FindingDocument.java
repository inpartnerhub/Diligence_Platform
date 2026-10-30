package com.inpartnerhub.diligence.finding.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Source document of a finding, with the page or section the finding relies on (PRD FND-02). */
@Entity
@IdClass(FindingDocument.Key.class)
@Table(schema = "fnd", name = "finding_document")
class FindingDocument {

    record Key(UUID findingId, UUID documentId) {

        Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "finding_id")
    private UUID findingId;

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "page_reference")
    private String pageReference;

    private String note;

    @Column(name = "linked_by")
    private UUID linkedBy;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected FindingDocument() {
    }

    FindingDocument(UUID findingId, UUID documentId, UUID projectId, String pageReference, String note,
            UUID linkedBy) {
        this.findingId = findingId;
        this.documentId = documentId;
        this.projectId = projectId;
        this.linkedBy = linkedBy;
        change(pageReference, note);
    }

    void change(String pageReference, String note) {
        this.pageReference = pageReference;
        this.note = note;
        this.linkedAt = Instant.now();
    }

    UUID getDocumentId() {
        return documentId;
    }

    String getPageReference() {
        return pageReference;
    }

    String getNote() {
        return note;
    }

    UUID getLinkedBy() {
        return linkedBy;
    }

    Instant getLinkedAt() {
        return linkedAt;
    }
}
