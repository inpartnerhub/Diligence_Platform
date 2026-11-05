package com.inpartnerhub.diligence.finding.internal;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.shared.persistence.LikePattern;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

/**
 * The risk register as SQL (issue #58): filters, workstream scope and aggregates run in PostgreSQL instead of loading
 * every finding of a project. Row-level security still applies to every query.
 */
@Component
class FindingQueries {

    static final Sort BY_NUMBER = Sort.by("number");

    record HeatCell(int impact, int likelihood, long count) {
    }

    /** Aggregates of the filtered register for the heat map and KPI tiles; archived findings are not counted. */
    record Summary(long total, Map<RiskLevel, Long> levels, List<HeatCell> cells, Integer sourcedPercent) {
    }

    @PersistenceContext
    private EntityManager entityManager;

    /** Findings of the project in the caller's scope that match the filter. */
    static Specification<Finding> specification(UUID projectId, ProjectContext context,
            FindingService.FindingFilter filter) {
        Specification<Finding> spec = (root, query, cb) -> cb.equal(root.get("projectId"), projectId);
        boolean scoped = FindingAccess.isInternal(context) && !context.canManage() && !context.workstreams().isEmpty();
        if (scoped) {
            var workstreams = context.workstreams();
            spec = spec.and((root, query, cb) -> cb.or(cb.isNull(root.get("workstreamId")),
                    root.get("workstreamId").in(workstreams)));
        }
        if (filter.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), filter.status()));
        }
        if (filter.workstreamId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("workstreamId"), filter.workstreamId()));
        }
        if (filter.level() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("riskLevel"), filter.level()));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            var pattern = LikePattern.contains(filter.query());
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("number")), pattern, LikePattern.ESCAPE),
                    cb.like(cb.lower(root.get("title")), pattern, LikePattern.ESCAPE),
                    cb.like(cb.lower(root.get("category")), pattern, LikePattern.ESCAPE)));
        }
        return spec;
    }

    Summary summarize(Specification<Finding> spec) {
        Specification<Finding> counted = spec.and(
                (root, query, cb) -> cb.notEqual(root.get("status"), FindingStatus.ARCHIVED));
        var cb = entityManager.getCriteriaBuilder();

        var grouped = cb.createTupleQuery();
        var root = grouped.from(Finding.class);
        grouped.select(cb.tuple(root.get("impact"), root.get("likelihood"), root.get("riskLevel"), cb.count(root)))
                .where(counted.toPredicate(root, grouped, cb))
                .groupBy(root.get("impact"), root.get("likelihood"), root.get("riskLevel"));

        long total = 0;
        var levels = new EnumMap<RiskLevel, Long>(RiskLevel.class);
        var cells = new ArrayList<HeatCell>();
        for (Tuple row : entityManager.createQuery(grouped).getResultList()) {
            var impact = row.get(0, Short.class);
            var likelihood = row.get(1, Short.class);
            var level = row.get(2, RiskLevel.class);
            long count = row.get(3, Long.class);
            total += count;
            if (level != null) {
                levels.merge(level, count, Long::sum);
            }
            if (impact != null && likelihood != null) {
                cells.add(new HeatCell(impact, likelihood, count));
            }
        }

        Integer sourcedPercent = null;
        if (total > 0) {
            var sourcedQuery = cb.createQuery(Long.class);
            var finding = sourcedQuery.from(Finding.class);
            var link = sourcedQuery.subquery(Integer.class);
            var document = link.from(FindingDocument.class);
            link.select(cb.literal(1)).where(cb.equal(document.get("findingId"), finding.get("id")));
            sourcedQuery.select(cb.count(finding))
                    .where(cb.and(counted.toPredicate(finding, sourcedQuery, cb), cb.exists(link)));
            long sourced = entityManager.createQuery(sourcedQuery).getSingleResult();
            sourcedPercent = (int) Math.round(100.0 * sourced / total);
        }
        return new Summary(total, levels, cells, sourcedPercent);
    }
}
