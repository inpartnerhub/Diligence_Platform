package com.inpartnerhub.diligence.finding;

/** Risk levels and their follow-up policy (PRD Appendix A). */
public enum RiskLevel {

    /** Green — recorded; no specific action. */
    LOW,
    /** Yellow — included in the report with mitigation recommendations. */
    MEDIUM,
    /** Orange — escalate to the PM; discuss price or warranty adjustments with the client. */
    HIGH,
    /** Red — escalate immediately to the Partner and client; potential deal breaker. */
    CRITICAL
}
