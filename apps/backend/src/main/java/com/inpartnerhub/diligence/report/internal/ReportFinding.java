package com.inpartnerhub.diligence.report.internal;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Which released findings a report version contains, in order (traceability of the report to the register). */
@Entity
@IdClass(ReportFinding.Key.class)
@Table(schema = "rpt", name = "report_finding")
class ReportFinding {

    record Key(UUID reportId, UUID findingId) {

        Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "report_id")
    private UUID reportId;

    @Id
    @Column(name = "finding_id")
    private UUID findingId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(nullable = false, updatable = false)
    private int position;

    protected ReportFinding() {
    }

    ReportFinding(UUID reportId, UUID findingId, UUID projectId, int position) {
        this.reportId = reportId;
        this.findingId = findingId;
        this.projectId = projectId;
        this.position = position;
    }
}
