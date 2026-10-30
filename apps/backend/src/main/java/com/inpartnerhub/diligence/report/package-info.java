/**
 * Reporting (PRD RPT-01, part of RPT-02): Word reports built from the released findings — cover, executive summary
 * with counts per risk level and a 5 × 5 heat map, findings per workstream by descending risk with their sources —
 * plus a PDF rendition (Gotenberg). Every generation is a new version; a partner issues it to the client, who
 * downloads a PDF watermarked with their identity (DOC-04). Issued reports are immutable (row-level security, V11).
 */
@ApplicationModule(displayName = "Reporting", allowedDependencies = { "shared", "security", "admin", "project",
        "finding", "dataroom", "ingestion", "notification" })
package com.inpartnerhub.diligence.report;

import org.springframework.modulith.ApplicationModule;
