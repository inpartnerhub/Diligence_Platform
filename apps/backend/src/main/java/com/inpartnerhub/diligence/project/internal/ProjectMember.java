package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.util.UUID;

import com.inpartnerhub.diligence.project.ProjectRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "prj", name = "project_member")
class ProjectMember {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "project_role", nullable = false)
    private ProjectRole role;

    @Column(name = "workstream_id")
    private UUID workstreamId;

    @Column(name = "access_expires_at")
    private Instant accessExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProjectMember() {
    }

    ProjectMember(UUID projectId, UUID userId, ProjectRole role, UUID workstreamId, Instant accessExpiresAt) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.userId = userId;
        this.role = role;
        this.workstreamId = workstreamId;
        this.accessExpiresAt = accessExpiresAt;
        this.createdAt = Instant.now();
    }

    boolean isActiveAt(Instant at) {
        return accessExpiresAt == null || accessExpiresAt.isAfter(at);
    }

    /** PRD Bab 5 stage 8: external access is revoked when the project is closed and archived. */
    void expireAt(Instant at) {
        if (accessExpiresAt == null || accessExpiresAt.isAfter(at)) {
            accessExpiresAt = at;
        }
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getUserId() {
        return userId;
    }

    ProjectRole getRole() {
        return role;
    }

    UUID getWorkstreamId() {
        return workstreamId;
    }

    Instant getAccessExpiresAt() {
        return accessExpiresAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
