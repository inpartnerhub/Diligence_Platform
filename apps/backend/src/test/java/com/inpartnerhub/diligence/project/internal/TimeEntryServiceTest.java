package com.inpartnerhub.diligence.project.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.admin.Directory.UserSummary;
import com.inpartnerhub.diligence.project.ProjectEvents.TimeEntryCreated;
import com.inpartnerhub.diligence.project.ProjectEvents.TimeEntryDeleted;
import com.inpartnerhub.diligence.project.ProjectEvents.TimeEntryUpdated;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

class TimeEntryServiceTest {

    private final TimeEntryRepository timeEntries = mock(TimeEntryRepository.class);
    private final ProjectAccess access = mock(ProjectAccess.class);
    private final WorkstreamRepository workstreams = mock(WorkstreamRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final Directory directory = mock(Directory.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final TimeEntryService service = new TimeEntryService(timeEntries, access, workstreams, currentUser,
            directory, events);

    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Project testProject = new Project("PRJ-TIME", "Time Tracking Test", UUID.randomUUID(),
            UUID.randomUUID(), LocalDate.now(), LocalDate.now().plusMonths(3), null, userId, 365);
    private final Workstream finWorkstream = new Workstream(projectId, WorkstreamType.FINANCIAL);

    @BeforeEach
    void setUp() {
        when(access.visible(projectId)).thenReturn(testProject);
        when(access.editable(projectId)).thenReturn(testProject);
        when(currentUser.requireCurrentUser()).thenReturn(new AuthenticatedUser(userId, "consultant", "consultant@example.com", "Consultant", Set.of(Role.ANALYST)));
        when(workstreams.findByProjectIdOrderByType(projectId)).thenReturn(List.of(finWorkstream));
        when(timeEntries.save(any(TimeEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void recordTimeSavesEntryAndPublishesEvent() {
        when(access.rolesOf(testProject)).thenReturn(Set.of(ProjectRole.ANALYST));

        var entry = service.recordTime(projectId, WorkstreamType.FINANCIAL, LocalDate.of(2026, 11, 1),
                BigDecimal.valueOf(4.50), "Financial model review");

        assertThat(entry.getProjectId()).isEqualTo(projectId);
        assertThat(entry.getUserId()).isEqualTo(userId);
        assertThat(entry.getWorkstreamType()).isEqualTo(WorkstreamType.FINANCIAL);
        assertThat(entry.getHours()).isEqualByComparingTo(BigDecimal.valueOf(4.50));
        assertThat(entry.getDescription()).isEqualTo("Financial model review");

        var captor = ArgumentCaptor.forClass(TimeEntryCreated.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(projectId);
        assertThat(captor.getValue().workstreamType()).isEqualTo(WorkstreamType.FINANCIAL);
    }

    @Test
    void recordTimeRejectsExternalUsers() {
        when(access.rolesOf(testProject)).thenReturn(Set.of(ProjectRole.CLIENT));

        assertThatThrownBy(() -> service.recordTime(projectId, WorkstreamType.FINANCIAL, LocalDate.now(),
                BigDecimal.valueOf(2.0), "Notes"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only project consultants can log time entries");
    }

    @Test
    void recordTimeRejectsUnconfiguredWorkstream() {
        when(access.rolesOf(testProject)).thenReturn(Set.of(ProjectRole.ANALYST));

        assertThatThrownBy(() -> service.recordTime(projectId, WorkstreamType.TAX, LocalDate.now(),
                BigDecimal.valueOf(2.0), "Tax review"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Workstream TAX is not configured");
    }

    @Test
    void recordTimeRejectsInvalidHours() {
        when(access.rolesOf(testProject)).thenReturn(Set.of(ProjectRole.ANALYST));

        assertThatThrownBy(() -> service.recordTime(projectId, WorkstreamType.FINANCIAL, LocalDate.now(),
                BigDecimal.valueOf(25.0), "Over limit"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Hours must be greater than 0 and less than or equal to 24");

        assertThatThrownBy(() -> service.recordTime(projectId, WorkstreamType.FINANCIAL, LocalDate.now(),
                BigDecimal.ZERO, "Zero hours"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void listValidatesDateRange() {
        var from = LocalDate.of(2026, 11, 10);
        var to = LocalDate.of(2026, 11, 1);

        assertThatThrownBy(() -> service.list(projectId, from, to, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Start date cannot be after end date");
    }

    @Test
    void summaryAggregatesWorkload() {
        var entry1 = new TimeEntry(projectId, userId, WorkstreamType.FINANCIAL, LocalDate.now(), BigDecimal.valueOf(5.0), "Part 1");
        var user2 = UUID.randomUUID();
        var entry2 = new TimeEntry(projectId, user2, WorkstreamType.FINANCIAL, LocalDate.now(), BigDecimal.valueOf(3.5), "Part 2");

        when(timeEntries.findByProjectIdOrderByEntryDateDescCreatedAtDesc(projectId))
                .thenReturn(List.of(entry1, entry2));
        when(directory.findUsers(any())).thenReturn(List.of(
                new UserSummary(userId, "u1@example.com", "Alice", UUID.randomUUID(), Role.ANALYST, true),
                new UserSummary(user2, "u2@example.com", "Bob", UUID.randomUUID(), Role.PROJECT_MANAGER, true)));

        var summary = service.summary(projectId, null, null);

        assertThat(summary.totalHours()).isEqualByComparingTo(BigDecimal.valueOf(8.5));
        assertThat(summary.byWorkstream().get(WorkstreamType.FINANCIAL)).isEqualByComparingTo(BigDecimal.valueOf(8.5));
        assertThat(summary.byConsultant()).hasSize(2);
        assertThat(summary.byConsultant().get(0).userName()).isEqualTo("Alice");
        assertThat(summary.byConsultant().get(0).totalHours()).isEqualByComparingTo(BigDecimal.valueOf(5.0));
    }

    @Test
    void updateModifiesEntryWhenOwnerOrManager() {
        var entry = new TimeEntry(projectId, userId, WorkstreamType.FINANCIAL, LocalDate.now(), BigDecimal.valueOf(4.0), "Old");
        when(timeEntries.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(access.rolesOf(testProject)).thenReturn(Set.of(ProjectRole.ANALYST));

        var updated = service.update(projectId, entry.getId(), WorkstreamType.FINANCIAL, LocalDate.now(),
                BigDecimal.valueOf(6.5), "Updated task");

        assertThat(updated.getHours()).isEqualByComparingTo(BigDecimal.valueOf(6.5));
        assertThat(updated.getDescription()).isEqualTo("Updated task");
        verify(events).publishEvent(any(TimeEntryUpdated.class));
    }

    @Test
    void deleteRemovesEntryAndPublishesEvent() {
        var entry = new TimeEntry(projectId, userId, WorkstreamType.FINANCIAL, LocalDate.now(), BigDecimal.valueOf(4.0), "To delete");
        when(timeEntries.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(access.rolesOf(testProject)).thenReturn(Set.of(ProjectRole.ANALYST));

        service.delete(projectId, entry.getId());

        verify(timeEntries).delete(entry);
        verify(events).publishEvent(any(TimeEntryDeleted.class));
    }

    @Test
    void exportCsvEscapesFormulasAndIncludesHeaders() throws Exception {
        var entry = new TimeEntry(projectId, userId, WorkstreamType.FINANCIAL, LocalDate.of(2026, 11, 1),
                BigDecimal.valueOf(7.5), "=cmd|' /C calc'!A0");
        when(timeEntries.findByProjectIdOrderByEntryDateDescCreatedAtDesc(projectId))
                .thenReturn(List.of(entry));
        when(directory.findUsers(any())).thenReturn(List.of(
                new UserSummary(userId, "u1@example.com", "Alice Consultant", UUID.randomUUID(), Role.ANALYST, true)));

        var writer = new StringWriter();
        service.exportCsv(projectId, null, null, writer);

        var csv = writer.toString();
        assertThat(csv).startsWith("\uFEFF\"Date\",\"Consultant\",\"Workstream\",\"Hours\",\"Description\"");
        assertThat(csv).contains("\"2026-11-01\",\"Alice Consultant\",\"FINANCIAL\",\"7.5\"");
        assertThat(csv).contains("\"'=cmd|' /C calc'!A0\""); // formula protection
    }
}
