package com.inpartnerhub.diligence.project.internal;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.project.internal.TimeEntryDto.ConsultantWorkloadResponse;
import com.inpartnerhub.diligence.project.internal.TimeEntryDto.CreateTimeEntryRequest;
import com.inpartnerhub.diligence.project.internal.TimeEntryDto.TimeEntryResponse;
import com.inpartnerhub.diligence.project.internal.TimeEntryDto.TimeEntrySummaryResponse;
import com.inpartnerhub.diligence.project.internal.TimeEntryDto.UpdateTimeEntryRequest;
import com.inpartnerhub.diligence.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}/time-entries")
@Tag(name = "Projects")
class TimeEntryController {

    private final TimeEntryService timeEntries;
    private final Directory directory;

    TimeEntryController(TimeEntryService timeEntries, Directory directory) {
        this.timeEntries = timeEntries;
        this.directory = directory;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record time spent on a project workstream (PRD PRJ-06)")
    @PreAuthorize("hasAnyRole('PARTNER', 'PROJECT_MANAGER', 'ANALYST')")
    TimeEntryResponse create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateTimeEntryRequest request) {
        var entry = timeEntries.recordTime(projectId, request.workstreamType(), request.entryDate(), request.hours(),
                request.description());
        var user = directory.findUser(entry.getUserId()).orElse(null);
        var userName = user != null ? user.displayName() : "User " + entry.getUserId().toString().substring(0, 8);
        return toResponse(entry, userName);
    }

    @GetMapping
    @Operation(summary = "List time entries for a project with optional date range and user filters")
    @PreAuthorize("hasAnyRole('PARTNER', 'PROJECT_MANAGER', 'ANALYST', 'OBSERVER')")
    List<TimeEntryResponse> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID userId) {
        var entries = timeEntries.list(projectId, from, to, userId);
        var userIds = entries.stream().map(TimeEntry::getUserId).distinct().toList();
        var userMap = directory.findUsers(userIds).stream()
                .collect(Collectors.toMap(Directory.UserSummary::id, Directory.UserSummary::displayName));

        return entries.stream()
                .map(e -> toResponse(e, userMap.getOrDefault(e.getUserId(), "User " + e.getUserId().toString().substring(0, 8))))
                .toList();
    }

    @GetMapping("/summary")
    @Operation(summary = "Get aggregated workload summary by workstream and consultant")
    @PreAuthorize("hasAnyRole('PARTNER', 'PROJECT_MANAGER', 'ANALYST', 'OBSERVER')")
    TimeEntrySummaryResponse summary(
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var summary = timeEntries.summary(projectId, from, to);
        var workloads = summary.byConsultant().stream()
                .map(w -> new ConsultantWorkloadResponse(w.userId(), w.userName(), w.totalHours(), w.entryCount()))
                .toList();
        return new TimeEntrySummaryResponse(summary.totalHours(), summary.byWorkstream(), workloads);
    }

    @PutMapping("/{entryId}")
    @Operation(summary = "Update an existing time entry")
    @PreAuthorize("hasAnyRole('PARTNER', 'PROJECT_MANAGER', 'ANALYST')")
    TimeEntryResponse update(
            @PathVariable UUID projectId,
            @PathVariable UUID entryId,
            @Valid @RequestBody UpdateTimeEntryRequest request) {
        var entry = timeEntries.update(projectId, entryId, request.workstreamType(), request.entryDate(),
                request.hours(), request.description());
        var user = directory.findUser(entry.getUserId()).orElse(null);
        var userName = user != null ? user.displayName() : "User " + entry.getUserId().toString().substring(0, 8);
        return toResponse(entry, userName);
    }

    @DeleteMapping("/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a time entry")
    @PreAuthorize("hasAnyRole('PARTNER', 'PROJECT_MANAGER', 'ANALYST')")
    void delete(
            @PathVariable UUID projectId,
            @PathVariable UUID entryId) {
        timeEntries.delete(projectId, entryId);
    }

    @GetMapping("/export")
    @Operation(summary = "Export project timesheet as CSV")
    @PreAuthorize("hasAnyRole('PARTNER', 'PROJECT_MANAGER', 'ANALYST', 'OBSERVER')")
    ResponseEntity<StreamingResponseBody> export(
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        StreamingResponseBody body = outputStream -> {
            try (var writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                timeEntries.exportCsv(projectId, from, to, writer);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"timesheet.csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static TimeEntryResponse toResponse(TimeEntry entry, String userName) {
        return new TimeEntryResponse(
                entry.getId(),
                entry.getProjectId(),
                entry.getWorkstreamType(),
                entry.getUserId(),
                userName,
                entry.getEntryDate(),
                entry.getHours(),
                entry.getDescription(),
                entry.getCreatedAt(),
                entry.getUpdatedAt());
    }
}
