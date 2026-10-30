package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.util.UUID;

import com.inpartnerhub.diligence.project.WorkstreamType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "prj", name = "workstream")
class Workstream {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private WorkstreamType type;

    @Column(name = "lead_user_id")
    private UUID leadUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Workstream() {
    }

    Workstream(UUID projectId, WorkstreamType type) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.type = type;
        this.createdAt = Instant.now();
    }

    void assignLead(UUID userId) {
        this.leadUserId = userId;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    WorkstreamType getType() {
        return type;
    }

    UUID getLeadUserId() {
        return leadUserId;
    }
}
