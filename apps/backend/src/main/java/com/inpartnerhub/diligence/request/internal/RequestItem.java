package com.inpartnerhub.diligence.request.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.inpartnerhub.diligence.request.RequestEvents.ItemRef;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** One requested document or piece of information (PRD REQ-01). */
@Entity
@Table(schema = "req", name = "request_item")
class RequestItem {

    static final int MAX_CATEGORY = 200;
    static final int MAX_DESCRIPTION = 2000;
    static final int MAX_WAVE = 20;

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "workstream_id", nullable = false)
    private UUID workstreamId;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestPriority priority;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;

    @Column(name = "assignee_user_id")
    private UUID assigneeUserId;

    @Column(nullable = false)
    private short wave;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_reminded_at")
    private LocalDate lastRemindedAt;

    @Version
    private long version;

    protected RequestItem() {
    }

    RequestItem(UUID projectId, UUID workstreamId, String number, String category, String description,
            RequestPriority priority, LocalDate dueDate, int wave) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.workstreamId = workstreamId;
        this.number = number;
        this.status = RequestStatus.NOT_REQUESTED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(workstreamId, category, description, priority, dueDate, wave);
    }

    void update(UUID workstreamId, String category, String description, RequestPriority priority, LocalDate dueDate,
            int wave) {
        this.workstreamId = workstreamId;
        this.category = category == null || category.isBlank() ? "General" : category.strip();
        this.description = description.strip();
        this.priority = priority == null ? RequestPriority.MEDIUM : priority;
        this.dueDate = dueDate;
        this.wave = (short) wave;
        touch();
    }

    void assign(UUID userId) {
        this.assigneeUserId = userId;
        touch();
    }

    void moveTo(RequestStatus next, Instant at) {
        if (next == RequestStatus.REQUESTED && requestedAt == null) {
            requestedAt = at;
        }
        this.status = next;
        this.updatedAt = at;
    }

    void reminded(LocalDate day) {
        this.lastRemindedAt = day;
    }

    void touch() {
        this.updatedAt = Instant.now();
    }

    ItemRef ref() {
        return new ItemRef(id, number, description, workstreamId, assigneeUserId);
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

    String getNumber() {
        return number;
    }

    String getCategory() {
        return category;
    }

    String getDescription() {
        return description;
    }

    RequestPriority getPriority() {
        return priority;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    RequestStatus getStatus() {
        return status;
    }

    UUID getAssigneeUserId() {
        return assigneeUserId;
    }

    int getWave() {
        return wave;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getRequestedAt() {
        return requestedAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    LocalDate getLastRemindedAt() {
        return lastRemindedAt;
    }
}
