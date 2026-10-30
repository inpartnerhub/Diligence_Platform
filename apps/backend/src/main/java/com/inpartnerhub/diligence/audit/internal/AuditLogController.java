package com.inpartnerhub.diligence.audit.internal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.shared.web.ApiPaths;
import com.inpartnerhub.diligence.shared.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1 + "/admin/audit-logs")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Audit")
class AuditLogController {

    record AuditLogResponse(UUID id, Instant occurredAt, UUID userId, String action, String objectType,
            String objectId, String ipAddress, String userAgent, String outcome, String details) {

        static AuditLogResponse of(AuditLogEntry e) {
            return new AuditLogResponse(e.getId(), e.getOccurredAt(), e.getUserId(), e.getAction(),
                    e.getObjectType(), e.getObjectId(), e.getIpAddress(), e.getUserAgent(), e.getOutcome(),
                    e.getDetails());
        }
    }

    private final AuditLogRepository repository;

    AuditLogController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Search the audit trail by user, object, action and time range — ADM-03, ADM-04")
    PageResponse<AuditLogResponse> search(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 50, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Specification<AuditLogEntry> spec = (root, query, cb) -> cb.conjunction();
        if (userId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("userId"), userId));
        }
        if (objectType != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("objectType"), objectType));
        }
        if (objectId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("objectId"), objectId));
        }
        if (action != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("action"), action));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("occurredAt"), to));
        }
        return PageResponse.of(repository.findAll(spec, pageable), AuditLogResponse::of);
    }
}
