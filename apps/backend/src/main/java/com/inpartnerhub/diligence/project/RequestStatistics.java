package com.inpartnerhub.diligence.project;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read model of request-list progress for dashboards (PRD PRJ-04), implemented by the request module (which depends
 * on this module, not the other way round). Results respect row-level security.
 */
public interface RequestStatistics {

    /** Item counts per workstream and status ({@code NOT_REQUESTED}, {@code REQUESTED}, … {@code CLOSED}). */
    List<WorkstreamProgress> progressByWorkstream(UUID projectId);

    /** Open items (requested, partially delivered or rejected) due within {@code days} of {@code today}, and overdue ones. */
    DeadlineSummary deadlines(UUID projectId, LocalDate today, int days);

    record WorkstreamProgress(UUID workstreamId, Map<String, Long> countsByStatus) {

        public WorkstreamProgress {
            countsByStatus = Map.copyOf(countsByStatus);
        }
    }

    record DeadlineSummary(long dueSoon, long overdue) {
    }
}
