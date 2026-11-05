package com.inpartnerhub.diligence.qna.internal;

import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.shared.persistence.LikePattern;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The Q&A log as SQL (issue #58): filters and workstream scope run in PostgreSQL instead of loading every question of
 * a project. Row-level security still decides which questions the target and the client see.
 */
@Component
class QuestionQueries {

    static final Sort BY_NUMBER = Sort.by("number");

    /** @param pendingApproval questions of the filtered log waiting for PM/partner approval (QNA-02) */
    record Summary(long total, long pendingApproval) {
    }

    @PersistenceContext
    private EntityManager entityManager;

    static Specification<Question> specification(UUID projectId, ProjectContext context,
            QuestionService.QuestionFilter filter) {
        Specification<Question> spec = (root, query, cb) -> cb.equal(root.get("projectId"), projectId);
        if (!QnaAccess.isClient(context) && !context.canManage() && !context.workstreams().isEmpty()) {
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
        if (filter.query() != null && !filter.query().isBlank()) {
            var pattern = LikePattern.contains(filter.query());
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("number")), pattern, LikePattern.ESCAPE),
                    cb.like(cb.lower(root.get("body")), pattern, LikePattern.ESCAPE)));
        }
        return spec;
    }

    Summary summarize(Specification<Question> filtered) {
        long total = count(filtered);
        long pending = count(filtered.and(
                (root, query, cb) -> cb.equal(root.get("status"), QuestionStatus.PENDING_APPROVAL)));
        return new Summary(total, pending);
    }

    private long count(Specification<Question> spec) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(Long.class);
        var root = query.from(Question.class);
        query.select(cb.count(root)).where(spec.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getSingleResult();
    }
}
