package com.inpartnerhub.diligence.project.internal;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.project.ProjectEvents.TimeEntryCreated;
import com.inpartnerhub.diligence.project.ProjectEvents.TimeEntryDeleted;
import com.inpartnerhub.diligence.project.ProjectEvents.TimeEntryUpdated;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

@Service
@Transactional
class TimeEntryService {

    private final TimeEntryRepository timeEntries;
    private final ProjectAccess access;
    private final WorkstreamRepository workstreams;
    private final CurrentUserProvider currentUser;
    private final Directory directory;
    private final ApplicationEventPublisher events;

    TimeEntryService(TimeEntryRepository timeEntries, ProjectAccess access, WorkstreamRepository workstreams,
            CurrentUserProvider currentUser, Directory directory, ApplicationEventPublisher events) {
        this.timeEntries = timeEntries;
        this.access = access;
        this.workstreams = workstreams;
        this.currentUser = currentUser;
        this.directory = directory;
        this.events = events;
    }

    TimeEntry recordTime(UUID projectId, WorkstreamType workstreamType, LocalDate entryDate, BigDecimal hours,
            String description) {
        var project = access.editable(projectId);
        validateWorkstream(projectId, workstreamType);
        validateHours(hours);

        var user = currentUser.requireCurrentUser();
        var roles = access.rolesOf(project);
        if (roles.stream().noneMatch(r -> r == ProjectRole.PARTNER || r == ProjectRole.PROJECT_MANAGER
                || r == ProjectRole.ANALYST)) {
            throw new AccessDeniedException("Only project consultants can log time entries");
        }

        var entry = new TimeEntry(projectId, user.id(), workstreamType, entryDate, hours, description);
        entry = timeEntries.save(entry);

        events.publishEvent(new TimeEntryCreated(projectId, entry.getId(), user.id(), workstreamType, hours, entryDate,
                RequestMetadata.capture()));
        return entry;
    }

    @Transactional(readOnly = true)
    List<TimeEntry> list(UUID projectId, LocalDate from, LocalDate to, UUID userId) {
        access.visible(projectId);

        if (from != null && to != null) {
            if (from.isAfter(to)) {
                throw new InvalidRequestException("Start date cannot be after end date");
            }
            if (userId != null) {
                return timeEntries.findByProjectIdAndUserIdAndEntryDateBetweenOrderByEntryDateDesc(projectId, userId, from, to);
            }
            return timeEntries.findByProjectIdAndEntryDateBetweenOrderByEntryDateDesc(projectId, from, to);
        }

        if (userId != null) {
            return timeEntries.findByProjectIdAndUserIdOrderByEntryDateDesc(projectId, userId);
        }
        return timeEntries.findByProjectIdOrderByEntryDateDescCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    TimeEntrySummary summary(UUID projectId, LocalDate from, LocalDate to) {
        access.visible(projectId);
        var entries = list(projectId, from, to, null);

        var totalHours = entries.stream()
                .map(TimeEntry::getHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<WorkstreamType, BigDecimal> byWorkstream = new EnumMap<>(WorkstreamType.class);
        for (var ws : WorkstreamType.values()) {
            byWorkstream.put(ws, BigDecimal.ZERO);
        }
        for (var entry : entries) {
            byWorkstream.compute(entry.getWorkstreamType(), (k, v) -> v == null ? entry.getHours() : v.add(entry.getHours()));
        }

        // Group by user
        Map<UUID, List<TimeEntry>> byUserMap = entries.stream().collect(Collectors.groupingBy(TimeEntry::getUserId));
        List<ConsultantWorkload> consultantWorkloads = new ArrayList<>();

        var userIds = new ArrayList<>(byUserMap.keySet());
        var userSummaryMap = directory.findUsers(userIds).stream()
                .collect(Collectors.toMap(Directory.UserSummary::id, Directory.UserSummary::displayName));

        for (var entry : byUserMap.entrySet()) {
            var userId = entry.getKey();
            var userEntries = entry.getValue();
            var userHours = userEntries.stream()
                    .map(TimeEntry::getHours)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            var userName = userSummaryMap.getOrDefault(userId, "User " + userId.toString().substring(0, 8));
            consultantWorkloads.add(new ConsultantWorkload(userId, userName, userHours, userEntries.size()));
        }
        consultantWorkloads.sort(Comparator.comparing(ConsultantWorkload::totalHours).reversed());

        return new TimeEntrySummary(totalHours, byWorkstream, consultantWorkloads);
    }

    TimeEntry update(UUID projectId, UUID entryId, WorkstreamType workstreamType, LocalDate entryDate,
            BigDecimal hours, String description) {
        var project = access.editable(projectId);
        validateWorkstream(projectId, workstreamType);
        validateHours(hours);

        var entry = timeEntries.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("TimeEntry", entryId));
        if (!entry.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("TimeEntry", entryId);
        }

        var user = currentUser.requireCurrentUser();
        var roles = access.rolesOf(project);
        boolean isManager = roles.stream().anyMatch(ProjectRole.MANAGERS::contains);
        if (!entry.getUserId().equals(user.id()) && !isManager) {
            throw new AccessDeniedException("You can only modify your own time entries");
        }

        entry.update(workstreamType, entryDate, hours, description);
        entry = timeEntries.save(entry);

        events.publishEvent(new TimeEntryUpdated(projectId, entry.getId(), user.id(), hours, RequestMetadata.capture()));
        return entry;
    }

    void delete(UUID projectId, UUID entryId) {
        var project = access.editable(projectId);
        var entry = timeEntries.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("TimeEntry", entryId));
        if (!entry.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("TimeEntry", entryId);
        }

        var user = currentUser.requireCurrentUser();
        var roles = access.rolesOf(project);
        boolean isManager = roles.stream().anyMatch(ProjectRole.MANAGERS::contains);
        if (!entry.getUserId().equals(user.id()) && !isManager) {
            throw new AccessDeniedException("You can only delete your own time entries");
        }

        timeEntries.delete(entry);
        events.publishEvent(new TimeEntryDeleted(projectId, entry.getId(), user.id(), RequestMetadata.capture()));
    }

    @Transactional(readOnly = true)
    void exportCsv(UUID projectId, LocalDate from, LocalDate to, Writer writer) throws IOException {
        var entries = list(projectId, from, to, null);

        var userIds = entries.stream().map(TimeEntry::getUserId).distinct().toList();
        var userSummaryMap = directory.findUsers(userIds).stream()
                .collect(Collectors.toMap(Directory.UserSummary::id, Directory.UserSummary::displayName));

        writer.write("\uFEFF");
        writer.write(String.join(",",
                csvCell("Date"),
                csvCell("Consultant"),
                csvCell("Workstream"),
                csvCell("Hours"),
                csvCell("Description")));
        writer.write("\r\n");

        for (var entry : entries) {
            var userName = userSummaryMap.getOrDefault(entry.getUserId(), entry.getUserId().toString());
            writer.write(String.join(",",
                    csvCell(entry.getEntryDate().toString()),
                    csvCell(userName),
                    csvCell(entry.getWorkstreamType().name()),
                    csvCell(entry.getHours().toPlainString()),
                    csvCell(entry.getDescription() != null ? entry.getDescription() : "")));
            writer.write("\r\n");
        }
        writer.flush();
    }

    private void validateWorkstream(UUID projectId, WorkstreamType workstreamType) {
        var existing = workstreams.findByProjectIdOrderByType(projectId);
        if (existing.stream().noneMatch(w -> w.getType() == workstreamType)) {
            throw new InvalidRequestException("Workstream " + workstreamType + " is not configured for this project");
        }
    }

    private void validateHours(BigDecimal hours) {
        if (hours == null || hours.compareTo(BigDecimal.ZERO) <= 0
                || hours.compareTo(BigDecimal.valueOf(24)) > 0) {
            throw new InvalidRequestException("Hours must be greater than 0 and less than or equal to 24");
        }
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "\"\"";
        }
        var escaped = value.replace("\"", "\"\"");
        if (escaped.startsWith("=") || escaped.startsWith("+") || escaped.startsWith("-") || escaped.startsWith("@")) {
            escaped = "'" + escaped;
        }
        return "\"" + escaped + "\"";
    }

    record TimeEntrySummary(BigDecimal totalHours, Map<WorkstreamType, BigDecimal> byWorkstream,
            List<ConsultantWorkload> byConsultant) {
    }

    record ConsultantWorkload(UUID userId, String userName, BigDecimal totalHours, int entryCount) {
    }
}
