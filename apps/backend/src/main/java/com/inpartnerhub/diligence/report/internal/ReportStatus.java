package com.inpartnerhub.diligence.report.internal;

/** Report states; the MVP uses DRAFT and ISSUED (tiered approval, RPT-03, follows in phase 2). */
enum ReportStatus {
    DRAFT, IN_REVIEW, APPROVED, ISSUED
}
