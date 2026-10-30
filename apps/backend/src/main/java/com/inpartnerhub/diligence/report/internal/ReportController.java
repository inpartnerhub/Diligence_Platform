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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}/reports")
@Tag(name = "Reports")
class ReportController {

    record ReportResponse(UUID id, int versionNumber, String title, ReportStatus status, String language,
            int findingCount, Long sizeBytes, String createdByName, Instant createdAt, String issuedByName,
            Instant issuedAt) {
    }

    record GenerateRequest(@NotBlank @Size(max = ReportService.MAX_TITLE) String title,
            @Pattern(regexp = "id|en") String language) {
    }

    private final ReportService reports;
    private final Directory directory;

    ReportController(ReportService reports, Directory directory) {
        this.reports = reports;
        this.directory = directory;
    }

    @GetMapping
    @Operation(summary = "Report versions (client: issued only), newest first — RPT-01")
    List<ReportResponse> list(@PathVariable UUID projectId) {
        var list = reports.list(projectId);
        var names = names(list.stream().flatMap(r -> Stream.of(r.getCreatedBy(), r.getIssuedBy()))
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

    @PostMapping("/{reportId}/issue")
    @Operation(summary = "Issue a draft to the client (partners) — RPT-01")
    ReportResponse issue(@PathVariable UUID projectId, @PathVariable UUID reportId) {
        var report = reports.issue(projectId, reportId);
        return response(report, names(List.of(report.getCreatedBy(), report.getIssuedBy())));
    }

    @DeleteMapping("/{reportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a draft version")
    void delete(@PathVariable UUID projectId, @PathVariable UUID reportId) {
        reports.delete(projectId, reportId);
    }

    @GetMapping("/{reportId}/download")
    @Operation(summary = "Download Word or PDF (team); the client gets the issued PDF watermarked for them; logged")
    ResponseEntity<StreamingResponseBody> download(@PathVariable UUID projectId, @PathVariable UUID reportId,
            @RequestParam(defaultValue = "pdf") String format) {
        var parsed = switch (format.toLowerCase(Locale.ROOT)) {
            case "pdf" -> ReportService.Format.PDF;
            case "docx" -> ReportService.Format.DOCX;
            default -> throw new InvalidRequestException("Format must be pdf or docx");
        };
        var download = reports.download(projectId, reportId, parsed);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(download.contentType()))
                .body(out -> download.writer().writeTo(out));
    }

    private static ReportResponse response(Report r, Map<UUID, String> names) {
        return new ReportResponse(r.getId(), r.getVersionNumber(), r.getTitle(), r.getStatus(), r.getLanguage(),
                r.getFindingCount(), r.getSizeBytes(), names.get(r.getCreatedBy()), r.getCreatedAt(),
                r.getIssuedBy() == null ? null : names.get(r.getIssuedBy()), r.getIssuedAt());
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
