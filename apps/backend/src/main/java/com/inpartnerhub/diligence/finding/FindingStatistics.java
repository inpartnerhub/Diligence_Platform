package com.inpartnerhub.diligence.finding;

import java.util.Map;
import java.util.UUID;

/**
 * Read model of the risk register for dashboards (PRD PRJ-04). Respects row-level security: target-side users
 * always get zero counts, clients only count released findings.
 */
public interface FindingStatistics {

    /** Finding counts per {@link RiskLevel} name, plus {@code UNRATED} for findings without a rating yet. */
    Map<String, Long> countsByRiskLevel(UUID projectId);
}
