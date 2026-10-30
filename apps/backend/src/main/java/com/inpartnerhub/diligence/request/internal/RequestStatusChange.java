package com.inpartnerhub.diligence.request.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One step in an item's status history (PRD REQ-02, REQ-06; basis of the data-collection KPI). */
@Entity
@Table(schema = "req", name = "request_item_status_history")
class RequestStatusChange {

    @Id
    private UUID id;

    @Column(name = "request_item_id", nullable = false, updatable = false)
    private UUID requestItemId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private RequestStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private RequestStatus toStatus;

    @Column(nullable = false, updatable = false)
    private short wave;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(updatable = false)
    private String note;

    protected RequestStatusChange() {
    }

    RequestStatusChange(RequestItem item, RequestStatus fromStatus, UUID changedBy, Instant at, String note) {
        this.id = UUID.randomUUID();
        this.requestItemId = item.getId();
        this.projectId = item.getProjectId();
        this.fromStatus = fromStatus;
        this.toStatus = item.getStatus();
        this.wave = (short) item.getWave();
        this.changedBy = changedBy;
        this.changedAt = at;
        this.note = note;
    }

    RequestStatus getFromStatus() {
        return fromStatus;
    }

    RequestStatus getToStatus() {
        return toStatus;
    }

    int getWave() {
        return wave;
    }

    UUID getChangedBy() {
        return changedBy;
    }

    Instant getChangedAt() {
        return changedAt;
    }

    String getNote() {
        return note;
    }
}
