package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Instant;
import java.util.UUID;

import com.inpartnerhub.diligence.dataroom.ScanResults;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "dr", name = "document_version")
class DocumentVersion {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "detected_content_type")
    private String detectedContentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, updatable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false)
    private ScanStatus scanStatus;

    @Column(name = "scan_detail")
    private String scanDetail;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "preview_status", nullable = false)
    private PreviewState previewStatus;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "text_extracted", nullable = false)
    private boolean textExtracted;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected DocumentVersion() {
    }

    DocumentVersion(UUID id, UUID documentId, UUID projectId, int versionNumber, String objectKey, String fileName,
            String contentType, long sizeBytes, String sha256, UUID uploadedBy) {
        this.id = id;
        this.documentId = documentId;
        this.projectId = projectId;
        this.versionNumber = versionNumber;
        this.objectKey = objectKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = Instant.now();
        this.scanStatus = ScanStatus.PENDING;
        this.previewStatus = PreviewState.PENDING;
    }

    void recordScan(ScanStatus status, String detectedContentType, String detail, Instant at) {
        this.scanStatus = status;
        this.detectedContentType = detectedContentType;
        this.scanDetail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
        this.scannedAt = at;
    }

    /** What the pipeline produced for a clean version; versions that are not clean never get a preview. */
    void recordRendition(ScanResults.Rendition rendition) {
        this.previewStatus = PreviewState.valueOf(rendition.preview().name());
        this.pageCount = rendition.preview() == ScanResults.PreviewStatus.READY ? rendition.pageCount() : null;
        this.textExtracted = rendition.textExtracted();
    }

    void noRendition() {
        this.previewStatus = PreviewState.UNAVAILABLE;
        this.pageCount = null;
        this.textExtracted = false;
    }

    boolean isDetectedPdf() {
        return detectedContentType != null && detectedContentType.split(";")[0].strip().equals("application/pdf");
    }

    UUID getId() {
        return id;
    }

    UUID getDocumentId() {
        return documentId;
    }

    UUID getProjectId() {
        return projectId;
    }

    int getVersionNumber() {
        return versionNumber;
    }

    String getObjectKey() {
        return objectKey;
    }

    String getFileName() {
        return fileName;
    }

    String getContentType() {
        return contentType;
    }

    String getDetectedContentType() {
        return detectedContentType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    String getSha256() {
        return sha256;
    }

    ScanStatus getScanStatus() {
        return scanStatus;
    }

    String getScanDetail() {
        return scanDetail;
    }

    Instant getScannedAt() {
        return scannedAt;
    }

    PreviewState getPreviewStatus() {
        return previewStatus;
    }

    int getPageCount() {
        return pageCount == null ? 0 : pageCount;
    }

    boolean isTextExtracted() {
        return textExtracted;
    }

    UUID getUploadedBy() {
        return uploadedBy;
    }

    Instant getUploadedAt() {
        return uploadedAt;
    }
}
