package com.inpartnerhub.diligence.audit.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.inpartnerhub.diligence.audit.AuditEvents.AuditLogExported;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
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
    private final AuditCsvExport csv;
    private final ApplicationEventPublisher events;

    AuditLogController(AuditLogRepository repository, AuditCsvExport csv, ApplicationEventPublisher events) {
        this.repository = repository;
        this.csv = csv;
        this.events = events;
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
        var spec = buildSpecification(userId, objectType, objectId, action, from, to);
        return PageResponse.of(repository.findAll(spec, pageable), AuditLogResponse::of);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Transactional // the export is audited (AuditLogExported) when this transaction commits
    @Operation(summary = "Export the audit trail as CSV, streamed newest first — ADM-04")
    ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        var spec = buildSpecification(userId, objectType, objectId, action, from, to);

        Map<String, String> filterDetails = new LinkedHashMap<>();
        if (userId != null) filterDetails.put("userId", userId.toString());
        if (objectType != null) filterDetails.put("objectType", objectType);
        if (objectId != null) filterDetails.put("objectId", objectId);
        if (action != null) filterDetails.put("action", action);
        if (from != null) filterDetails.put("from", from.toString());
        if (to != null) filterDetails.put("to", to.toString());

        events.publishEvent(new AuditLogExported(RequestMetadata.capture(), filterDetails));

        String filename = "audit-logs-"
                + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
                + ".csv";
        // Streamed in batches (#116): the whole audit log never sits in memory.
        StreamingResponseBody body = out -> csv.write(spec, out);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    private Specification<AuditLogEntry> buildSpecification(
            UUID userId, String objectType, String objectId, String action, Instant from, Instant to) {
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
        return spec;
    }
}
