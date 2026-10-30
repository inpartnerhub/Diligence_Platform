/**
 * Findings and risk register (PRD FND-01…FND-04): findings per workstream with an impact × likelihood rating from
 * the configurable risk matrix (Appendix A), links to source documents (page), internal review notes and the
 * validation workflow Draft → In review → Approved → Released (four-eyes approval, release by a partner).
 * Findings are never visible to target-side users and clients only see released ones — enforced by row-level
 * security (V5, V10), not only by the UI (PRD Bab 3, information barrier). Implements
 * {@link com.inpartnerhub.diligence.project.FindingStatistics} for project dashboards.
 */
@ApplicationModule(displayName = "Findings", allowedDependencies = { "shared", "security", "admin", "project",
        "dataroom", "notification" })
package com.inpartnerhub.diligence.finding;

import org.springframework.modulith.ApplicationModule;
