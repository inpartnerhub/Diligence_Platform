package com.inpartnerhub.diligence.finding;

import java.util.Map;
import java.util.UUID;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Audited finding events (PRD ADM-03); they also drive notifications and escalations (Appendix A). */
public final class FindingEvents {

    private FindingEvents() {
    }

    /** Snapshot of a finding for listeners that run after the transaction. */
    public record FindingRef(UUID findingId, String number, String title, UUID workstreamId, UUID authorId) {
    }

    public record FindingChanged(UUID projectId, FindingRef finding, String change, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "finding." + change;
        }

        @Override
        public String auditObjectType() {
            return "finding";
        }

        @Override
        public String auditObjectId() {
            return finding.findingId().toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString(), "number", finding.number());
        }
    }

    /** @param note  reason for returning, reopening or archiving; {@code null} otherwise */
    public record FindingStatusChanged(UUID projectId, String projectCode, FindingRef finding, String fromStatus,
            String toStatus, String note, RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "finding.status-changed";
        }

        @Override
        public String auditObjectType() {
            return "finding";
        }

        @Override
        public String auditObjectId() {
            return finding.findingId().toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString(), "number", finding.number(), "from", fromStatus,
                    "to", toStatus);
        }
    }

    /** A finding was rated (or re-rated) HIGH or CRITICAL: escalation per PRD Appendix A. */
    public record FindingEscalated(UUID projectId, String projectCode, FindingRef finding, RiskLevel level,
            int score, RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "finding.escalated";
        }

        @Override
        public String auditObjectType() {
            return "finding";
        }

        @Override
        public String auditObjectId() {
            return finding.findingId().toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString(), "level", level.name(), "score", String.valueOf(score));
        }
    }

    /** Administrators changed the thresholds; {@code rerated} open findings got a new score/level (FND-03). */
    public record RiskMatrixChanged(RiskMatrix matrix, int rerated, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "risk-matrix.updated";
        }

        @Override
        public String auditObjectType() {
            return "risk-matrix";
        }

        @Override
        public String auditObjectId() {
            return "default";
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("mediumFrom", String.valueOf(matrix.mediumFrom()), "highFrom",
                    String.valueOf(matrix.highFrom()), "criticalFrom", String.valueOf(matrix.criticalFrom()),
                    "rerated", String.valueOf(rerated));
        }
    }
}
