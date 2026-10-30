package com.inpartnerhub.diligence.finding;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Findings for other modules: Q&A links questions to findings (PRD FND-02, QNA-01) and the report builder reads the
 * released findings with their sources (RPT-01). Results follow the caller's visibility (row-level security).
 */
public interface FindingDirectory {

    /** Empty when the finding does not exist in the project or the caller may not see it. */
    Optional<FindingSummary> find(UUID projectId, UUID findingId);

    /** Released findings of the project (optionally one workstream), by workstream and descending risk. */
    List<ReleasedFinding> released(UUID projectId, UUID workstreamId);

    /** The configured thresholds, for executive summaries and heat maps. */
    RiskMatrix riskMatrix();

    record FindingSummary(UUID id, String number, String title, UUID workstreamId, String status) {
    }

    record Source(String documentName, String pageReference) {
    }

    record ReleasedFinding(UUID id, String number, UUID workstreamId, String title, String category,
            String description, String impactDescription, BigDecimal monetaryImpact, String currency,
            String recommendation, Integer impact, Integer likelihood, Integer riskScore, RiskLevel riskLevel,
            List<Source> sources) {

        public ReleasedFinding {
            sources = List.copyOf(sources);
        }
    }
}
