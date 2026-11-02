package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * In-viewer annotation on a document page (PRD DOC-08), linked to a finding or review note.
 * Bounding box is stored as percentages of page width and height (0.0 to 100.0).
 */
@Entity
@Table(schema = "dr", name = "document_annotation")
class DocumentAnnotation {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "page_number", nullable = false)
    private int pageNumber;

    @Column(name = "x_pct", nullable = false)
    private double xPct;

    @Column(name = "y_pct", nullable = false)
    private double yPct;

    @Column(name = "width_pct", nullable = false)
    private double widthPct;

    @Column(name = "height_pct", nullable = false)
    private double heightPct;

    private String color;

    private String comment;

    @Column(name = "finding_id")
    private UUID findingId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentAnnotation() {
    }

    DocumentAnnotation(UUID id, UUID projectId, UUID documentId, int pageNumber, double xPct, double yPct,
            double widthPct, double heightPct, String color, String comment, UUID findingId, UUID createdBy,
            String createdByName) {
        this.id = id == null ? UUID.randomUUID() : id;
        this.projectId = projectId;
        this.documentId = documentId;
        this.pageNumber = pageNumber;
        this.xPct = xPct;
        this.yPct = yPct;
        this.widthPct = widthPct;
        this.heightPct = heightPct;
        this.color = color;
        this.comment = comment;
        this.findingId = findingId;
        this.createdBy = createdBy;
        this.createdByName = createdByName;
        var now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(Double xPct, Double yPct, Double widthPct, Double heightPct, String color, String comment,
            UUID findingId) {
        if (xPct != null) {
            this.xPct = xPct;
        }
        if (yPct != null) {
            this.yPct = yPct;
        }
        if (widthPct != null) {
            this.widthPct = widthPct;
        }
        if (heightPct != null) {
            this.heightPct = heightPct;
        }
        if (color != null) {
            this.color = color;
        }
        if (comment != null) {
            this.comment = comment;
        }
        this.findingId = findingId;
        this.updatedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getDocumentId() {
        return documentId;
    }

    int getPageNumber() {
        return pageNumber;
    }

    double getxPct() {
        return xPct;
    }

    double getyPct() {
        return yPct;
    }

    double getWidthPct() {
        return widthPct;
    }

    double getHeightPct() {
        return heightPct;
    }

    String getColor() {
        return color;
    }

    String getComment() {
        return comment;
    }

    UUID getFindingId() {
        return findingId;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
