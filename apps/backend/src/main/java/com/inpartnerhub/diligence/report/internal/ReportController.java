package com.inpartnerhub.diligence.report.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.shared.web.ApiPaths;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}/reports")
@Tag(name = "Reports")
class ReportController {

    record ReportResponse(
            UUID id,
            int versionNumber,
            int majorVersion,
            int minorVersion,
            String versionLabel,
            String title,
            ReportStatus status,
            String language,
            int findingCount,
            Long sizeBytes,
            String createdByName,
            Instant createdAt,
            String submittedByName,
            Instant submittedAt,
            String managerApprovedByName,
            Instant managerApprovedAt,
            String issuedByName,
            Instant issuedAt) {
    }

    record GenerateRequest(
            @NotBlank @Size(max = ReportService.MAX_TITLE) String title,
            @Pattern(regexp = "id|en") String language) {
    }

    record RevisionRequest(
            @NotNull Boolean major,
            @Size(max = ReportService.MAX_TITLE) String title,
            @Pattern(regexp = "id|en") String language) {
    }

    record ReviewActionRequest(
            @Size(max = 1000) String note) {
    }

    record ReturnRequest(
            @NotBlank @Size(max = 1000) String reason) {
    }

    record StatusHistoryResponse(
            UUID id,
            String fromStatus,
            String toStatus,
            String changedByName,
            Instant changedAt,
            String note) {
    }

    private final ReportService reports;
    private final Directory directory;

    ReportController(ReportService reports, Directory directory) {
        this.reports = reports;
        this.directory = directory;
    }

    @GetMapping
    @Operation(summary = "Report versions (client: released only), newest first — RPT-01, RPT-03")
    List<ReportResponse> list(@PathVariable UUID projectId) {
        var list = reports.list(projectId);
        var names = names(list.stream().flatMap(r -> Stream.of(
                r.getCreatedBy(), r.getSubmittedBy(), r.getManagerApprovedBy(), r.getIssuedBy()))
                .toList());
        return list.stream().map(r -> response(r, names)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Generate a new report version from the released findings (Word + PDF) — RPT-01")
    ReportResponse generate(@PathVariable UUID projectId, @Valid @RequestBody GenerateRequest request) {
        var report = reports.generate(projectId, request.title(), request.language());
        return response(report, names(List.of(report.getCreatedBy())));
    }

    @PostMapping("/{reportId}/revision")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Generate a new major or minor revision from released findings — RPT-03")
    ReportResponse generateRevision(
            @PathVariable UUID projectId,
            @PathVariable UUID reportId,
            @Valid @RequestBody RevisionRequest request) {
        var report = reports.generateRevision(projectId, reportId, request.title(), Boolean.TRUE.equals(request.major()),
                request.language());
        return response(report, names(List.of(report.getCreatedBy())));
    }

    @PostMapping("/{reportId}/submit")
    @Operation(summary = "Submit a draft report for manager review — RPT-03")
    ReportResponse submit(
            @PathVariable UUID projectId,
            @PathVariable UUID reportId,
            @RequestBody(required = false) ReviewActionRequest request) {
        var note = request != null ? request.note() : null;
        var report = reports.submitForReview(projectId, reportId, note);
        return response(report, names(List.of(report.getCreatedBy(), report.getSubmittedBy())));
    }

    @PostMapping("/{reportId}/approve")
    @Operation(summary = "Manager approves report under four-eyes principle — RPT-03")
    ReportResponse approve(
            @PathVariable UUID projectId,
            @PathVariable UUID reportId,
            @RequestBody(required = false) ReviewActionRequest request) {
        var note = request != null ? request.note() : null;
        var report = reports.approveByManager(projectId, reportId, note);
        return response(report,
                names(List.of(report.getCreatedBy(), report.getSubmittedBy(), report.getManagerApprovedBy())));
    }

    @PostMapping("/{reportId}/release")
    @Operation(summary = "Lead partner releases approved report to client — RPT-03")
    ReportResponse release(
            @PathVariable UUID projectId,
            @PathVariable UUID reportId,
            @RequestBody(required = false) ReviewActionRequest request) {
        var note = request != null ? request.note() : null;
        var report = reports.releaseByPartner(projectId, reportId, note);
        return response(report, names(List.of(report.getCreatedBy(), report.getSubmittedBy(),
                report.getManagerApprovedBy(), report.getIssuedBy())));
    }

    @PostMapping("/{reportId}/return")
    @Operation(summary = "Return report to draft with review feedback note — RPT-03")
    ReportResponse returnDraft(
            @PathVariable UUID projectId,
            @PathVariable UUID reportId,
            @Valid @RequestBody ReturnRequest request) {
        var report = reports.returnDraft(projectId, reportId, request.reason());
        return response(report, names(List.of(report.getCreatedBy())));
    }

    @PostMapping("/{reportId}/issue")
    @Operation(summary = "Issue a report to the client (partners) — legacy alias for release — RPT-01")
    ReportResponse issue(@PathVariable UUID projectId, @PathVariable UUID reportId) {
        var report = reports.issue(projectId, reportId);
        return response(report, names(List.of(report.getCreatedBy(), report.getIssuedBy())));
    }

    @GetMapping("/{reportId}/history")
    @Operation(summary = "Report approval and revision audit history — RPT-03")
    List<StatusHistoryResponse> history(
            @PathVariable UUID projectId,
            @PathVariable UUID reportId) {
        var list = reports.history(projectId, reportId);
        var names = names(list.stream().map(ReportStatusHistory::getChangedBy).toList());
        return list.stream()
                .map(h -> new StatusHistoryResponse(h.getId(), h.getFromStatus(), h.getToStatus(),
                        names.get(h.getChangedBy()), h.getChangedAt(), h.getNote()))
                .toList();
    }

    @DeleteMapping("/{reportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a draft version")
    void delete(@PathVariable UUID projectId, @PathVariable UUID reportId) {
        reports.delete(projectId, reportId);
    }

    @GetMapping("/{reportId}/download")
    @Operation(summary = "Download Word, PDF, or PowerPoint presentation; the client gets the issued PDF watermarked for them; logged")
    ResponseEntity<StreamingResponseBody> download(@PathVariable UUID projectId, @PathVariable UUID reportId,
            @RequestParam(defaultValue = "pdf") String format) {
        var parsed = switch (format.toLowerCase(Locale.ROOT)) {
            case "pdf" -> ReportService.Format.PDF;
            case "docx" -> ReportService.Format.DOCX;
            case "pptx" -> ReportService.Format.PPTX;
            default -> throw new InvalidRequestException("Format must be pdf, docx, or pptx");
        };
        var download = reports.download(projectId, reportId, parsed);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(download.contentType()))
                .body(out -> download.writer().writeTo(out));
    }

    @GetMapping("/{reportId}/export-pptx")
    @Operation(summary = "Export findings and executive summaries to PowerPoint (PPTX) presentation — RPT-04")
    ResponseEntity<StreamingResponseBody> exportPowerPoint(@PathVariable UUID projectId, @PathVariable UUID reportId) {
        var download = reports.download(projectId, reportId, ReportService.Format.PPTX);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(download.contentType()))
                .body(out -> download.writer().writeTo(out));
    }

    private static ReportResponse response(Report r, Map<UUID, String> names) {
        return new ReportResponse(
                r.getId(),
                r.getVersionNumber(),
                r.getMajorVersion(),
                r.getMinorVersion(),
                r.getVersionLabel(),
                r.getTitle(),
                r.getStatus(),
                r.getLanguage(),
                r.getFindingCount(),
                r.getSizeBytes(),
                names.get(r.getCreatedBy()),
                r.getCreatedAt(),
                r.getSubmittedBy() == null ? null : names.get(r.getSubmittedBy()),
                r.getSubmittedAt(),
                r.getManagerApprovedBy() == null ? null : names.get(r.getManagerApprovedBy()),
                r.getManagerApprovedAt(),
                r.getIssuedBy() == null ? null : names.get(r.getIssuedBy()),
                r.getIssuedAt());
    }

    private Map<UUID, String> names(List<UUID> ids) {
        var names = new HashMap<UUID, String>();
        var wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (!wanted.isEmpty()) {
            directory.findUsers(wanted).forEach(u -> names.putIfAbsent(u.id(),
                    u.displayName() == null || u.displayName().isBlank() ? u.email() : u.displayName()));
        }
        return names;
    }
}
