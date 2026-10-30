/**
 * Findings and risk register (PRD FND-01…FND-06). Sprint 1–2 provides the risk rating model (Appendix A);
 * findings themselves arrive in sprint 11–12. Findings are never visible to target-side users — enforced by
 * row-level security, not only by the UI (PRD Bab 3, information barrier).
 */
@ApplicationModule(displayName = "Findings", allowedDependencies = "shared")
package com.inpartnerhub.diligence.finding;

import org.springframework.modulith.ApplicationModule;
