package com.inpartnerhub.diligence.project;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Read model of the risk register for dashboards (PRD PRJ-04), implemented by the finding module (which depends on
 * this module, not the other way round). Respects row-level security: target-side users always get zero counts,
 * clients only count released findings.
 */
public interface FindingStatistics {

    /** Finding counts per risk level name (LOW, MEDIUM, HIGH, CRITICAL), plus {@code UNRATED} for findings without a rating yet. */
    Map<String, Long> countsByRiskLevel(UUID projectId);

    /** {@link #countsByRiskLevel(UUID)} for many projects in one query (portfolio, #122); absent = no findings. */
    Map<UUID, Map<String, Long>> countsByRiskLevel(Collection<UUID> projectIds);
}
