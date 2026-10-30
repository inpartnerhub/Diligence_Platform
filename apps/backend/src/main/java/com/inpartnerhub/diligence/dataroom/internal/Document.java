package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A named document in a folder; its content lives in versions (PRD DOC-07). Mirrors the current version. */
@Entity
@Table(schema = "dr", name = "document")
class Document {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "folder_id", nullable = false)
    private UUID folderId;

    @Column(nullable = false)
    private String name;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false)
    private ScanStatus scanStatus;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Document() {
    }

    Document(UUID projectId, UUID folderId, String name, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.folderId = folderId;
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.scanStatus = ScanStatus.PENDING;
    }

    void useVersion(DocumentVersion version) {
        this.currentVersionId = version.getId();
        this.contentType = version.getContentType();
        this.sizeBytes = version.getSizeBytes();
        this.sha256 = version.getSha256();
        this.scanStatus = version.getScanStatus();
    }

    void syncStatus(DocumentVersion version) {
        if (version.getId().equals(currentVersionId)) {
            this.scanStatus = version.getScanStatus();
        }
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getFolderId() {
        return folderId;
    }

    String getName() {
        return name;
    }

    String getContentType() {
        return contentType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    String getSha256() {
        return sha256;
    }

    UUID getCurrentVersionId() {
        return currentVersionId;
    }

    ScanStatus getScanStatus() {
        return scanStatus;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
