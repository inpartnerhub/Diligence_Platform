package com.inpartnerhub.diligence.project.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.inpartnerhub.diligence.project.WorkstreamType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "prj", name = "time_entry")
class TimeEntry {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "workstream_type", nullable = false)
    private WorkstreamType workstreamType;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal hours;

    @Column
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TimeEntry() {
    }

    TimeEntry(UUID projectId, UUID userId, WorkstreamType workstreamType, LocalDate entryDate, BigDecimal hours,
            String description) {
        this.id = UUID.randomUUID();
        this.projectId = Objects.requireNonNull(projectId);
        this.userId = Objects.requireNonNull(userId);
        this.workstreamType = Objects.requireNonNull(workstreamType);
        this.entryDate = Objects.requireNonNull(entryDate);
        this.hours = Objects.requireNonNull(hours);
        this.description = description;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    void update(WorkstreamType workstreamType, LocalDate entryDate, BigDecimal hours, String description) {
        this.workstreamType = Objects.requireNonNull(workstreamType);
        this.entryDate = Objects.requireNonNull(entryDate);
        this.hours = Objects.requireNonNull(hours);
        this.description = description;
        this.updatedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    WorkstreamType getWorkstreamType() {
        return workstreamType;
    }

    UUID getUserId() {
        return userId;
    }

    LocalDate getEntryDate() {
        return entryDate;
    }

    BigDecimal getHours() {
        return hours;
    }

    String getDescription() {
        return description;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
