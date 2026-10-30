package com.inpartnerhub.diligence.request.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.request.RequestEvents.RequestListTransferred;
import com.inpartnerhub.diligence.request.internal.RequestSpreadsheet.Column;
import com.inpartnerhub.diligence.request.internal.RequestSpreadsheet.ImportRow;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

/**
 * Excel import (PRD REQ-01) and Excel/PDF export (REQ-05) of a project's request list. Imports are validated as a
 * whole: a dry run reports every row's outcome, and nothing is stored unless every row is valid. PDF exports are the
 * Excel export converted by Gotenberg (LibreOffice), so both formats show the same columns.
 */
@Service
@Transactional
class RequestTransferService {

    enum Format { XLSX, PDF }

    enum Action { CREATE, UPDATE }

    record ExportFile(String fileName, String contentType, byte[] content) {
    }

    record RowResult(int row, String number, Action action, List<String> errors) {
    }

    record ImportResult(boolean committed, int created, int updated, int invalid, List<RowResult> rows) {
    }

    private record Planned(ImportRow source, String number, Action action, UUID workstreamId,
            WorkstreamType workstream, RequestPriority priority, int wave, RequestItem existing) {
    }

    private final RequestItemRepository items;
    private final RequestStatusChangeRepository history;
    private final RequestService requests;
    private final RequestAccess access;
    private final ProjectDirectory projects;
    private final Directory directory;
    private final PreviewRenderer renderer;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;
    private final ZoneId zone;

    RequestTransferService(RequestItemRepository items, RequestStatusChangeRepository history, RequestService requests, RequestAccess access,
            ProjectDirectory projects, Directory directory, PreviewRenderer renderer, CurrentUserProvider currentUser,
            ApplicationEventPublisher events, @Value("${diligence.request.reminder-zone:Asia/Jakarta}") ZoneId zone) {
        this.items = items;
        this.history = history;
        this.requests = requests;
        this.access = access;
        this.projects = projects;
        this.directory = directory;
        this.renderer = renderer;
        this.currentUser = currentUser;
        this.events = events;
        this.zone = zone;
    }

    // ------------------------------------------------------------------ export

    /**
     * The caller's view of the list (target: released items only) as Excel or PDF. Not read-only: the audit event
     * of the export is written to the event outbox in this transaction.
     */
    ExportFile export(UUID projectId, Format format) {
        var context = access.project(projectId);
        var project = projects.summary(projectId).orElseThrow();
        var list = requests.list(projectId, RequestService.ItemFilter.ALL);
        var counts = requests.documentCounts(projectId);
        var types = access.workstreams(projectId);
        var assignees = list.stream().map(RequestItem::getAssigneeUserId).filter(Objects::nonNull)
                .distinct().toList();
        var names = assignees.isEmpty() ? Map.<UUID, String>of() : directory.findUsers(assignees).stream()
                .collect(Collectors.toMap(Directory.UserSummary::id,
                        u -> u.displayName() == null ? u.email() : u.displayName(), (a, b) -> a));
        var rows = list.stream().map(i -> new RequestSpreadsheet.ExportRow(i.getNumber(),
                String.valueOf(types.get(i.getWorkstreamId())), i.getCategory(), i.getDescription(),
                i.getPriority().name(), i.getDueDate(), i.getWave(), i.getStatus().name(),
                i.getAssigneeUserId() == null ? null : names.get(i.getAssigneeUserId()),
                counts.getOrDefault(i.getId(), 0L), i.getRequestedAt(),
                i.getUpdatedAt())).toList();
        var xlsx = RequestSpreadsheet.export(context.code(), project.name(), rows, zone);
        var baseName = "request-list-" + context.code().toLowerCase(Locale.ROOT) + "-" + Instant.now()
                .atZone(zone).toLocalDate();
        events.publishEvent(new RequestListTransferred(projectId, "exported", format.name(), rows.size(),
                RequestMetadata.capture()));
        return switch (format) {
            case XLSX -> new ExportFile(baseName + ".xlsx", RequestSpreadsheet.CONTENT_TYPE, xlsx);
            case PDF -> new ExportFile(baseName + ".pdf", "application/pdf", toPdf(xlsx, baseName + ".xlsx"));
        };
    }

    @Transactional(readOnly = true)
    ExportFile template(UUID projectId) {
        var context = access.editableProject(projectId);
        var names = access.workstreams(projectId).values().stream().sorted().map(Enum::name).toList();
        return new ExportFile("request-list-template-" + context.code().toLowerCase(Locale.ROOT) + ".xlsx",
                RequestSpreadsheet.CONTENT_TYPE, RequestSpreadsheet.template(names));
    }

    private byte[] toPdf(byte[] xlsx, String fileName) {
        try {
            var target = Files.createTempFile("request-list", ".pdf");
            try {
                renderer.renderPdf(new ByteArrayInputStream(xlsx), fileName, RequestSpreadsheet.CONTENT_TYPE, target);
                return Files.readAllBytes(target);
            } finally {
                Files.deleteIfExists(target);
            }
        } catch (IOException | RuntimeException ex) {
            throw new UpstreamServiceException("The PDF export is not available right now", ex);
        }
    }

    // ------------------------------------------------------------------ import

    /**
     * Validates an Excel request list and, unless {@code dryRun}, stores it: new numbers (or empty numbers) create
     * items, existing numbers update their content. Nothing is stored when any row is invalid.
     */
    ImportResult importList(UUID projectId, InputStream content, boolean dryRun) {
        var context = access.editableProject(projectId);
        var rows = RequestSpreadsheet.read(content);
        if (rows.isEmpty()) {
            throw new InvalidRequestException("The sheet has no request items");
        }
        var byType = new HashMap<WorkstreamType, UUID>();
        access.workstreams(projectId).forEach((id, type) -> byType.put(type, id));
        var existing = items.findByProjectIdOrderByNumber(projectId).stream()
                .collect(Collectors.toMap(RequestItem::getNumber, i -> i));
        var usedNumbers = new HashSet<>(existing.keySet());
        var seen = new HashSet<String>();

        var results = new ArrayList<RowResult>();
        var planned = new ArrayList<Planned>();
        for (var row : rows) {
            var errors = new ArrayList<String>();
            var type = RequestNumbers.parseWorkstream(row.get(Column.WORKSTREAM)).orElse(null);
            UUID workstreamId = null;
            if (type == null) {
                errors.add(row.get(Column.WORKSTREAM) == null ? "Workstream is missing"
                        : "Unknown workstream '" + row.get(Column.WORKSTREAM) + "'");
            } else if (!byType.containsKey(type)) {
                errors.add("Workstream " + type + " is not part of this project");
            } else if (!context.coversWorkstream(byType.get(type))) {
                errors.add("Workstream " + type + " is outside your assignment");
            } else {
                workstreamId = byType.get(type);
            }
            var description = row.get(Column.DESCRIPTION);
            if (description == null) {
                errors.add("Description is missing");
            } else if (description.length() > RequestItem.MAX_DESCRIPTION) {
                errors.add("Description is longer than " + RequestItem.MAX_DESCRIPTION + " characters");
            }
            var category = row.get(Column.CATEGORY);
            if (category != null && category.length() > RequestItem.MAX_CATEGORY) {
                errors.add("Category is longer than " + RequestItem.MAX_CATEGORY + " characters");
            }
            var priority = priority(row.get(Column.PRIORITY), errors);
            var wave = wave(row.get(Column.WAVE), errors);
            if (row.dueDateError() != null) {
                errors.add(row.dueDateError());
            }

            var number = row.get(Column.NUMBER);
            RequestItem current = null;
            if (number != null) {
                if (!RequestNumbers.isValid(number)) {
                    errors.add("Invalid number '" + number + "'");
                } else if (!seen.add(number)) {
                    errors.add("Number " + number + " appears more than once");
                } else {
                    current = existing.get(number);
                    if (current != null && !context.coversWorkstream(current.getWorkstreamId())) {
                        errors.add("Item " + number + " is outside your assignment");
                    }
                }
            } else if (type != null && errors.isEmpty()) {
                number = RequestNumbers.next(type, usedNumbers);
            }
            if (number != null) {
                usedNumbers.add(number);
            }
            var action = current == null ? Action.CREATE : Action.UPDATE;
            results.add(new RowResult(row.rowNumber(), number, action, List.copyOf(errors)));
            if (errors.isEmpty()) {
                planned.add(new Planned(row, number, action, workstreamId, type, priority, wave, current));
            }
        }

        var invalid = (int) results.stream().filter(r -> !r.errors().isEmpty()).count();
        var created = (int) planned.stream().filter(p -> p.action() == Action.CREATE).count();
        var updated = planned.size() - created;
        if (dryRun || invalid > 0) {
            return new ImportResult(false, created, updated, invalid, results);
        }

        var userId = currentUser.requireCurrentUser().id();
        var now = Instant.now();
        for (var plan : planned) {
            var row = plan.source();
            if (plan.existing() == null) {
                var item = new RequestItem(projectId, plan.workstreamId(), plan.number(),
                        row.get(Column.CATEGORY), row.get(Column.DESCRIPTION), plan.priority(), row.dueDate(),
                        plan.wave());
                items.save(item);
                history.save(new RequestStatusChange(item, null, userId, now, "import"));
            } else {
                var item = plan.existing();
                var movedWorkstream = !item.getWorkstreamId().equals(plan.workstreamId());
                item.update(plan.workstreamId(), row.get(Column.CATEGORY), row.get(Column.DESCRIPTION),
                        plan.priority(), row.dueDate(), plan.wave());
                if (movedWorkstream && item.getAssigneeUserId() != null) {
                    item.assign(null); // the assignee may not cover the new workstream
                }
            }
        }
        items.flush();
        events.publishEvent(new RequestListTransferred(projectId, "imported", Format.XLSX.name(), planned.size(),
                RequestMetadata.capture()));
        return new ImportResult(true, created, updated, 0, results);
    }

    private static RequestPriority priority(String raw, List<String> errors) {
        if (raw == null) {
            return RequestPriority.MEDIUM;
        }
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "LOW", "RENDAH" -> RequestPriority.LOW;
            case "MEDIUM", "SEDANG" -> RequestPriority.MEDIUM;
            case "HIGH", "TINGGI" -> RequestPriority.HIGH;
            default -> {
                errors.add("Unknown priority '" + raw + "' (LOW, MEDIUM or HIGH)");
                yield RequestPriority.MEDIUM;
            }
        };
    }

    private static int wave(String raw, List<String> errors) {
        if (raw == null) {
            return 1;
        }
        try {
            var value = (int) Double.parseDouble(raw.strip());
            if (value >= 1 && value <= RequestItem.MAX_WAVE) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        errors.add("Wave must be a number from 1 to " + RequestItem.MAX_WAVE);
        return 1;
    }
}
