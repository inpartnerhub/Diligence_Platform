package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(schema = "dr", name = "folder")
class Folder {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Column(nullable = false)
    private String name;

    @Column(name = "workstream_id")
    private UUID workstreamId;

    @Column(name = "download_allowed", nullable = false)
    private boolean downloadAllowed;

    @Column(name = "print_allowed", nullable = false)
    private boolean printAllowed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Folder() {
    }

    Folder(UUID projectId, UUID parentId, String name, UUID workstreamId) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.parentId = parentId;
        this.name = name;
        this.workstreamId = workstreamId;
        this.createdAt = Instant.now();
    }

    void rename(String name) {
        this.name = name;
    }

    /** PRD DOC-05: download and print are denied unless explicitly allowed per folder. */
    void changePolicy(Boolean downloadAllowed, Boolean printAllowed) {
        if (downloadAllowed != null) {
            this.downloadAllowed = downloadAllowed;
        }
        if (printAllowed != null) {
            this.printAllowed = printAllowed;
        }
    }

    void assignWorkstream(UUID workstreamId) {
        this.workstreamId = workstreamId;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getParentId() {
        return parentId;
    }

    String getName() {
        return name;
    }

    UUID getWorkstreamId() {
        return workstreamId;
    }

    boolean isDownloadAllowed() {
        return downloadAllowed;
    }

    boolean isPrintAllowed() {
        return printAllowed;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
