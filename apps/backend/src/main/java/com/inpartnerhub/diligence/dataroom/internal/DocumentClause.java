package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Key contractual clause extracted from a document version (PRD DOC-09).
 */
@Entity
@Table(schema = "dr", name = "document_clause")
class DocumentClause {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "clause_type", nullable = false, length = 64)
    private String clauseType;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "page_number", nullable = false)
    private int pageNumber;

    @Column(name = "extracted_text", nullable = false)
    private String extractedText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentClause() {
    }

    DocumentClause(UUID id, UUID projectId, UUID documentId, UUID versionId, String clauseType, double confidence,
            int pageNumber, String extractedText, Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.documentId = documentId;
        this.versionId = versionId;
        this.clauseType = clauseType;
        this.confidence = confidence;
        this.pageNumber = pageNumber;
        this.extractedText = extractedText;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public String getClauseType() {
        return clauseType;
    }

    public double getConfidence() {
        return confidence;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
