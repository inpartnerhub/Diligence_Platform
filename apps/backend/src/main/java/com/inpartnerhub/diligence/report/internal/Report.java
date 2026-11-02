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

/** One generated version of a project's due diligence report (PRD RPT-01, RPT-03). */
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

    @Column(name = "major_version", nullable = false, updatable = false)
    private int majorVersion;

    @Column(name = "minor_version", nullable = false, updatable = false)
    private int minorVersion;

    @Column(name = "version_label", nullable = false, updatable = false)
    private String versionLabel;

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

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "manager_approved_by")
    private UUID managerApprovedBy;

    @Column(name = "manager_approved_at")
    private Instant managerApprovedAt;

    @Column(name = "issued_by")
    private UUID issuedBy;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Version
    private long version;

    protected Report() {
    }

    Report(UUID id, UUID projectId, UUID workstreamId, int versionNumber, int majorVersion, int minorVersion,
            String versionLabel, String title, String language, int findingCount, String docxKey, String pdfKey,
            long sizeBytes, UUID createdBy) {
        this.id = id;
        this.projectId = projectId;
        this.workstreamId = workstreamId;
        this.versionNumber = versionNumber;
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        this.versionLabel = versionLabel;
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

    Report(UUID id, UUID projectId, UUID workstreamId, int versionNumber, String title, String language,
            int findingCount, String docxKey, String pdfKey, long sizeBytes, UUID createdBy) {
        this(id, projectId, workstreamId, versionNumber, 1, 0, "v" + versionNumber + ".0", title, language,
                findingCount, docxKey, pdfKey, sizeBytes, createdBy);
    }

    void submitForReview(UUID userId, Instant at) {
        status = ReportStatus.SUBMITTED_FOR_REVIEW;
        submittedBy = userId;
        submittedAt = at;
    }

    void approveByManager(UUID userId, Instant at) {
        status = ReportStatus.APPROVED_BY_MANAGER;
        managerApprovedBy = userId;
        managerApprovedAt = at;
    }

    void releaseByPartner(UUID userId, Instant at) {
        status = ReportStatus.RELEASED_BY_PARTNER;
        issuedBy = userId;
        issuedAt = at;
    }

    void returnDraft(Instant at) {
        status = ReportStatus.DRAFT;
    }

    void issue(UUID userId, Instant at) {
        releaseByPartner(userId, at);
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

    int getMajorVersion() {
        return majorVersion;
    }

    int getMinorVersion() {
        return minorVersion;
    }

    String getVersionLabel() {
        return versionLabel != null ? versionLabel : "v" + versionNumber + ".0";
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

    UUID getSubmittedBy() {
        return submittedBy;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    UUID getManagerApprovedBy() {
        return managerApprovedBy;
    }

    Instant getManagerApprovedAt() {
        return managerApprovedAt;
    }

    UUID getIssuedBy() {
        return issuedBy;
    }

    Instant getIssuedAt() {
        return issuedAt;
    }

    long getVersion() {
        return version;
    }
}
