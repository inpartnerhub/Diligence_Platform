package com.inpartnerhub.diligence.project.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProjectRetentionServiceTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final ProjectRetentionService retentionService = new ProjectRetentionService(projects, projectService);

    @Test
    void purgesOnlyArchivedProjectsPastRetentionDeadline() {
        var now = Instant.parse("2027-01-01T00:00:00Z");

        // Expired project: archived 100 days ago, retention period is 30 days
        var expired = new Project("EXPIRED-1", "Expired Project", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1), null, UUID.randomUUID(), 30);
        expired.archive(now.minus(100, ChronoUnit.DAYS));

        // Active retention: archived 10 days ago, retention period is 90 days
        var active = new Project("ACTIVE-1", "Retained Project", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1), null, UUID.randomUUID(), 90);
        active.archive(now.minus(10, ChronoUnit.DAYS));

        when(projects.findArchivedCandidates()).thenReturn(List.of(expired, active));

        int purgedCount = retentionService.purgeExpiredProjects(now);

        assertThat(purgedCount).isEqualTo(1);
        verify(projectService).purgeExpired(eq(expired), eq(now));
        verify(projectService, never()).purgeExpired(eq(active), any());
    }

    @Test
    void skipsAlreadyPurgedProjects() {
        var now = Instant.parse("2027-01-01T00:00:00Z");
        var purgedProject = new Project("PURGED-1", "Purged Project", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1), null, UUID.randomUUID(), 30);
        purgedProject.archive(now.minus(100, ChronoUnit.DAYS));
        purgedProject.markPurged(now.minus(10, ChronoUnit.DAYS));

        when(projects.findArchivedCandidates()).thenReturn(List.of(purgedProject));

        int purgedCount = retentionService.purgeExpiredProjects(now);

        assertThat(purgedCount).isEqualTo(0);
        verify(projectService, never()).purgeExpired(any(), any());
    }
}

