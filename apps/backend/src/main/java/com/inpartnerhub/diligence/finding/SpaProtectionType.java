package com.inpartnerhub.diligence.finding;

/**
 * Contractual protection categories in the Sale and Purchase Agreement (PRD FND-05).
 */
public enum SpaProtectionType {
    /** Direct reduction from enterprise/equity value in purchase price negotiations. */
    PRICE_CHIP,
    /** Ring-fenced, uncapped or specific indemnification for identified historical liabilities. */
    SPECIAL_INDEMNITY,
    /** Condition precedent that must be fulfilled by the seller before closing occurs. */
    PRE_CLOSING_CONDITION,
    /** Post-closing affirmative or negative covenant to be performed within a set milestone. */
    POST_CLOSING_COVENANT,
    /** Specific representations and warranties clause coverage with appropriate disclosure carve-outs. */
    WARRANTY_REPRESENTATION
}
