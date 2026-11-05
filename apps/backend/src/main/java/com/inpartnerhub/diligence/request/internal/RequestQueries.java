package com.inpartnerhub.diligence.request.internal;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.shared.persistence.LikePattern;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The request list as SQL (issue #58): filters, workstream scope and the figures of the list page run in PostgreSQL
 * instead of loading every item of a project. Row-level security still applies (the target never sees unreleased
 * items).
 */
@Component
class RequestQueries {

    static final Sort BY_NUMBER = Sort.by("number");

    record Unreleased(int wave, UUID workstreamId, long count) {
    }

    /**
     * @param statuses   items of the filtered list per status
     * @param overdue    open items of the filtered list past their due date
     * @param waves      waves present in the caller's scope (filter options)
     * @param unreleased not yet requested items per wave and workstream (release dialog, REQ-06)
     */
    record Summary(Map<RequestStatus, Long> statuses, long overdue, List<Integer> waves,
            List<Unreleased> unreleased) {
    }

    @PersistenceContext
    private EntityManager entityManager;

    /** Items of the project in the caller's workstream scope. */
    static Specification<RequestItem> scope(UUID projectId, ProjectContext context) {
        Specification<RequestItem> spec = (root, query, cb) -> cb.equal(root.get("projectId"), projectId);
        if (!context.canManage() && !context.workstreams().isEmpty()) {
            var workstreams = context.workstreams();
            spec = spec.and((root, query, cb) -> cb.or(cb.isNull(root.get("workstreamId")),
                    root.get("workstreamId").in(workstreams)));
        }
        return spec;
    }

    static Specification<RequestItem> specification(UUID projectId, ProjectContext context,
            RequestService.ItemFilter filter) {
        var spec = scope(projectId, context);
        if (filter.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), filter.status()));
        }
        if (filter.workstreamId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("workstreamId"), filter.workstreamId()));
        }
        if (filter.wave() != null) {
            short wave = filter.wave().shortValue();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("wave"), wave));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            var pattern = LikePattern.contains(filter.query());
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("number")), pattern, LikePattern.ESCAPE),
                    cb.like(cb.lower(root.get("category")), pattern, LikePattern.ESCAPE),
                    cb.like(cb.lower(root.get("description")), pattern, LikePattern.ESCAPE)));
        }
        return spec;
    }

    Summary summarize(Specification<RequestItem> scope, Specification<RequestItem> filtered, LocalDate today) {
        var cb = entityManager.getCriteriaBuilder();

        var statusQuery = cb.createTupleQuery();
        var counted = statusQuery.from(RequestItem.class);
        statusQuery.select(cb.tuple(counted.get("status"), cb.count(counted)))
                .where(filtered.toPredicate(counted, statusQuery, cb))
                .groupBy(counted.get("status"));
        var statuses = new EnumMap<RequestStatus, Long>(RequestStatus.class);
        entityManager.createQuery(statusQuery).getResultList()
                .forEach(row -> statuses.put(row.get(0, RequestStatus.class), row.get(1, Long.class)));

        var overdueQuery = cb.createQuery(Long.class);
        var item = overdueQuery.from(RequestItem.class);
        overdueQuery.select(cb.count(item)).where(cb.and(filtered.toPredicate(item, overdueQuery, cb),
                item.get("status").in(RequestStatus.OPEN), cb.isNotNull(item.get("dueDate")),
                cb.lessThan(item.<LocalDate>get("dueDate"), today)));
        long overdue = entityManager.createQuery(overdueQuery).getSingleResult();

        var wavesQuery = cb.createQuery(Short.class);
        var waveRoot = wavesQuery.from(RequestItem.class);
        wavesQuery.select(waveRoot.get("wave")).distinct(true)
                .where(scope.toPredicate(waveRoot, wavesQuery, cb))
                .orderBy(cb.asc(waveRoot.get("wave")));
        var waves = entityManager.createQuery(wavesQuery).getResultList().stream().map(Short::intValue).toList();

        var unreleasedQuery = cb.createTupleQuery();
        var pending = unreleasedQuery.from(RequestItem.class);
        unreleasedQuery.select(cb.tuple(pending.get("wave"), pending.get("workstreamId"), cb.count(pending)))
                .where(cb.and(scope.toPredicate(pending, unreleasedQuery, cb),
                        cb.equal(pending.get("status"), RequestStatus.NOT_REQUESTED)))
                .groupBy(pending.get("wave"), pending.get("workstreamId"))
                .orderBy(cb.asc(pending.get("wave")));
        var unreleased = entityManager.createQuery(unreleasedQuery).getResultList().stream()
                .map(row -> new Unreleased(row.get(0, Short.class), row.get(1, UUID.class), row.get(2, Long.class)))
                .toList();

        return new Summary(statuses, overdue, waves, unreleased);
    }
}
