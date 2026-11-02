package com.inpartnerhub.diligence.report.internal;

/**
 * Report lifecycle stages (PRD RPT-01, RPT-03):
 * Tiered multi-stage approval: DRAFT -> SUBMITTED_FOR_REVIEW -> APPROVED_BY_MANAGER -> RELEASED_BY_PARTNER.
 * Legacy values (IN_REVIEW, APPROVED, ISSUED) are kept for backwards compatibility.
 */
public enum ReportStatus {
    DRAFT,
    SUBMITTED_FOR_REVIEW,
    APPROVED_BY_MANAGER,
    RELEASED_BY_PARTNER,
    IN_REVIEW,
    APPROVED,
    ISSUED;

    public boolean isReleased() {
        return this == RELEASED_BY_PARTNER || this == ISSUED;
    }
}
