package com.inpartnerhub.diligence.report.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** One generated version of a project's due diligence report (PRD RPT-01). */
@Entity
@Table(schema = "rpt", name = "report")
class Report {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "workstream_id", updatable = false)
    private UUID workstreamId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(nullable = false, updatable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false)
    private ReportStatus status;

    @Column(name = "object_key", updatable = false)
    private String docxKey;

    @Column(name = "pdf_object_key", updatable = false)
    private String pdfKey;

    @Column(nullable = false, updatable = false)
    private String language;

    @Column(name = "finding_count", nullable = false, updatable = false)
    private int findingCount;

    @Column(name = "size_bytes", updatable = false)
    private Long sizeBytes;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "issued_by")
    private UUID issuedBy;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Version
    private long version;

    protected Report() {
    }

    Report(UUID id, UUID projectId, UUID workstreamId, int versionNumber, String title, String language,
            int findingCount, String docxKey, String pdfKey, long sizeBytes, UUID createdBy) {
        this.id = id;
        this.projectId = projectId;
        this.workstreamId = workstreamId;
        this.versionNumber = versionNumber;
        this.title = title;
        this.language = language;
        this.findingCount = findingCount;
        this.docxKey = docxKey;
        this.pdfKey = pdfKey;
        this.sizeBytes = sizeBytes;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.status = ReportStatus.DRAFT;
    }

    void issue(UUID userId, Instant at) {
        status = ReportStatus.ISSUED;
        issuedBy = userId;
        issuedAt = at;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getWorkstreamId() {
        return workstreamId;
    }

    int getVersionNumber() {
        return versionNumber;
    }

    String getTitle() {
        return title;
    }

    ReportStatus getStatus() {
        return status;
    }

    String getDocxKey() {
        return docxKey;
    }

    String getPdfKey() {
        return pdfKey;
    }

    String getLanguage() {
        return language;
    }

    int getFindingCount() {
        return findingCount;
    }

    Long getSizeBytes() {
        return sizeBytes;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    UUID getIssuedBy() {
        return issuedBy;
    }

    Instant getIssuedAt() {
        return issuedAt;
    }
}
