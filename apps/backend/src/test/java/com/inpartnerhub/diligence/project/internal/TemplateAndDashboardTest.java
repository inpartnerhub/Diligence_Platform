package com.inpartnerhub.diligence.project.internal;

import static com.inpartnerhub.diligence.project.internal.ProjectTestData.project;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import com.inpartnerhub.diligence.finding.FindingStatistics;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.request.RequestStatistics;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

import tools.jackson.databind.json.JsonMapper;

class TemplateAndDashboardTest {

    private final ProjectTemplateRepository repository = mock(ProjectTemplateRepository.class);
    private final TemplateService templates = new TemplateService(repository, JsonMapper.builder().build());

    private static final TemplateDefinition DEFINITION = new TemplateDefinition(
            List.of(WorkstreamType.FINANCIAL, WorkstreamType.LEGAL),
            List.of(new TemplateDefinition.RequestItem(WorkstreamType.LEGAL, "Corporate", "Deed of establishment")),
            List.of(new TemplateDefinition.FindingCategory(WorkstreamType.FINANCIAL, "Quality of earnings")),
            List.of("01 Corporate", "02 Finance"));

    @Test
    void createsDuplicatesAndRoundTripsDefinitions() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var template = templates.create(" Full scope ", "All workstreams", DEFINITION);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));
        var copy = templates.duplicate(template.getId(), "Full scope (copy)");

        assertThat(template.getName()).isEqualTo("Full scope");
        assertThat(templates.definition(template.getId())).isEqualTo(DEFINITION);
        assertThat(templates.parse(copy.getDefinition())).isEqualTo(DEFINITION);
        assertThat(copy.getDescription()).isEqualTo("All workstreams");
    }

    @Test
    void updatesAndListsTemplates() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var template = templates.create("A", null, DEFINITION);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));
        when(repository.existsByNameIgnoreCase("Taken")).thenReturn(true);

        templates.update(template.getId(), "B", "desc", DEFINITION);
        assertThat(template.getName()).isEqualTo("B");
        assertThatThrownBy(() -> templates.update(template.getId(), "Taken", null, DEFINITION))
                .isInstanceOf(ResourceConflictException.class);

        when(repository.findAll(Sort.by("name"))).thenReturn(List.of(template));
        assertThat(templates.list()).containsExactly(template);
        assertThatThrownBy(() -> templates.get(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsInconsistentDefinitions() {
        when(repository.existsByNameIgnoreCase("Dup")).thenReturn(true);
        assertThatThrownBy(() -> templates.create("Dup", null, DEFINITION))
                .isInstanceOf(ResourceConflictException.class);

        assertThatThrownBy(() -> new TemplateDefinition(List.of(), null, null, null).validate())
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new TemplateDefinition(List.of(WorkstreamType.TAX, WorkstreamType.TAX), null,
                null, null).validate()).hasMessageContaining("repeat");
        assertThatThrownBy(() -> new TemplateDefinition(List.of(WorkstreamType.TAX),
                List.of(new TemplateDefinition.RequestItem(WorkstreamType.HR, "c", "d")), null, null).validate())
                .hasMessageContaining("workstream");
        assertThatThrownBy(() -> new TemplateDefinition(List.of(WorkstreamType.TAX),
                List.of(new TemplateDefinition.RequestItem(WorkstreamType.TAX, "c", " ")), null, null).validate())
                .hasMessageContaining("description");
        assertThatThrownBy(() -> new TemplateDefinition(List.of(WorkstreamType.TAX), null,
                List.of(new TemplateDefinition.FindingCategory(WorkstreamType.HR, "x")), null).validate())
                .hasMessageContaining("Finding category");
    }

    @Test
    void dashboardComputesCompletionFromRequestedItems() {
        var access = mock(ProjectAccess.class);
        var workstreams = mock(WorkstreamRepository.class);
        var members = mock(ProjectMemberRepository.class);
        var requests = mock(RequestStatistics.class);
        var findings = mock(FindingStatistics.class);
        var today = LocalDate.of(2026, 11, 2);
        var service = new DashboardService(access, workstreams, members, requests, findings,
                Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"), ZoneOffset.UTC));
        var project = project(UUID.randomUUID());
        var legal = new Workstream(project.getId(), WorkstreamType.LEGAL);
        var tax = new Workstream(project.getId(), WorkstreamType.TAX);
        when(access.visible(project.getId())).thenReturn(project);
        when(workstreams.findByProjectIdOrderByType(project.getId())).thenReturn(List.of(legal, tax));
        when(requests.progressByWorkstream(project.getId())).thenReturn(List.of(new RequestStatistics.WorkstreamProgress(
                legal.getId(), Map.of("NOT_REQUESTED", 2L, "REQUESTED", 3L, "RECEIVED", 4L, "CLOSED", 1L))));
        when(requests.deadlines(project.getId(), today, DashboardService.DUE_SOON_DAYS))
                .thenReturn(new RequestStatistics.DeadlineSummary(2, 1));
        when(findings.countsByRiskLevel(project.getId())).thenReturn(Map.of("HIGH", 3L));
        when(members.countByProjectId(project.getId())).thenReturn(5L);

        var dashboard = service.dashboard(project.getId());

        var legalProgress = dashboard.workstreams().getFirst();
        assertThat(legalProgress.total()).isEqualTo(10);
        assertThat(legalProgress.requested()).isEqualTo(8);
        assertThat(legalProgress.fulfilled()).isEqualTo(5);
        assertThat(legalProgress.completionPercent()).isEqualTo(63);
        assertThat(dashboard.workstreams().get(1).completionPercent()).isZero();
        assertThat(dashboard.completionPercent()).isEqualTo(63);
        assertThat(dashboard.dueSoon()).isEqualTo(2);
        assertThat(dashboard.overdue()).isEqualTo(1);
        assertThat(dashboard.findingsByRisk()).containsEntry("HIGH", 3L);
        assertThat(dashboard.memberCount()).isEqualTo(5);
    }
}
