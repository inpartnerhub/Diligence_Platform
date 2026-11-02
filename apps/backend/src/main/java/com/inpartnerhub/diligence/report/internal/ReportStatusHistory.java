package com.inpartnerhub.diligence.report.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Report review history and audit trail (PRD RPT-03).
 * Records transitions between approval stages along with approver identity and review notes.
 */
@Entity
@Table(schema = "rpt", name = "report_status_history")
class ReportStatusHistory {

    @Id
    private UUID id;

    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status", nullable = false)
    private String toStatus;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    private String note;

    protected ReportStatusHistory() {
    }

    ReportStatusHistory(UUID id, UUID reportId, UUID projectId, String fromStatus, String toStatus, UUID changedBy,
            String note) {
        this.id = id == null ? UUID.randomUUID() : id;
        this.reportId = reportId;
        this.projectId = projectId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.changedAt = Instant.now();
        this.note = note;
    }

    UUID getId() {
        return id;
    }

    UUID getReportId() {
        return reportId;
    }

    UUID getProjectId() {
        return projectId;
    }

    String getFromStatus() {
        return fromStatus;
    }

    String getToStatus() {
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
