package com.inpartnerhub.diligence.finding.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One step in a finding's validation history (PRD FND-04). Internal working paper. */
@Entity
@Table(schema = "fnd", name = "finding_status_history")
class FindingStatusChange {

    @Id
    private UUID id;

    @Column(name = "finding_id", nullable = false, updatable = false)
    private UUID findingId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private FindingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private FindingStatus toStatus;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(updatable = false)
    private String note;

    protected FindingStatusChange() {
    }

    FindingStatusChange(Finding finding, FindingStatus fromStatus, UUID changedBy, Instant at, String note) {
        this.id = UUID.randomUUID();
        this.findingId = finding.getId();
        this.projectId = finding.getProjectId();
        this.fromStatus = fromStatus;
        this.toStatus = finding.getStatus();
        this.changedBy = changedBy;
        this.changedAt = at;
        this.note = note;
    }

    FindingStatus getFromStatus() {
        return fromStatus;
    }

    FindingStatus getToStatus() {
        return toStatus;
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
