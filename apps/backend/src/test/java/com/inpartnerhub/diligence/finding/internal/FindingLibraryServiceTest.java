package com.inpartnerhub.diligence.finding.internal;

import static com.inpartnerhub.diligence.finding.internal.FindingTestData.LEGAL;
import static com.inpartnerhub.diligence.finding.internal.FindingTestData.PROJECT;
import static com.inpartnerhub.diligence.finding.internal.FindingTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.WorkstreamSummary;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

class FindingLibraryServiceTest {

    private final FindingLibraryTemplateRepository templates = mock(FindingLibraryTemplateRepository.class);
    private final FindingService findingService = mock(FindingService.class);
    private final FindingAccess access = mock(FindingAccess.class);
    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final FindingLibraryService service = new FindingLibraryService(templates, findingService, access, projects);

    private final UUID templateId = UUID.randomUUID();
    private final FindingLibraryTemplate template = new FindingLibraryTemplate(
            templateId, WorkstreamType.LEGAL, "Corporate", "Missing Minute Books",
            "Statutory minute books are missing", "Regularize minute books", 4, 3,
            Instant.now(), Instant.now());

    @BeforeEach
    void setUp() {
        when(templates.findById(templateId)).thenReturn(Optional.of(template));
        when(templates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(access.editableProject(PROJECT)).thenReturn(context(ProjectRole.ANALYST));
    }

    @Test
    void listFiltersByWorkstreamAndKeyword() {
        when(templates.findByWorkstreamTypeOrderByCategoryAscTitleAsc(WorkstreamType.LEGAL))
                .thenReturn(List.of(template));

        var results = service.list(WorkstreamType.LEGAL, "minute");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Missing Minute Books");

        var emptyResults = service.list(WorkstreamType.LEGAL, "nonexistent");
        assertThat(emptyResults).isEmpty();
    }

    @Test
    void getThrowsNotFoundForUnknown() {
        UUID unknown = UUID.randomUUID();
        when(templates.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createValidatesAndSavesTemplate() {
        var created = service.create(WorkstreamType.TAX, "Transfer Pricing", "Missing TP Documentation",
                "Standard description", "Standard recommendation", 4, 3);

        assertThat(created.getWorkstreamType()).isEqualTo(WorkstreamType.TAX);
        assertThat(created.getTitle()).isEqualTo("Missing TP Documentation");
        verify(templates).save(any());
    }

    @Test
    void createValidatesScale() {
        assertThatThrownBy(() -> service.create(WorkstreamType.TAX, "Category", "Title",
                "Desc", "Rec", 6, 3))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("defaultImpact");

        assertThatThrownBy(() -> service.create(WorkstreamType.TAX, "Category", "Title",
                "Desc", "Rec", 3, 0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("defaultLikelihood");
    }

    @Test
    void updateModifiesExistingTemplate() {
        var updated = service.update(templateId, WorkstreamType.LEGAL, "Corporate Governance",
                "Updated Title", "Updated Desc", "Updated Rec", 5, 2);

        assertThat(updated.getTitle()).isEqualTo("Updated Title");
        assertThat(updated.getDefaultImpact()).isEqualTo(5);
        assertThat(updated.getDefaultLikelihood()).isEqualTo(2);
        verify(templates).save(template);
    }

    @Test
    void deleteRemovesTemplate() {
        service.delete(templateId);
        verify(templates).delete(template);
    }

    @Test
    void instantiateCreatesFindingInProject() {
        var legalWorkstream = new WorkstreamSummary(LEGAL, WorkstreamType.LEGAL);
        when(projects.workstreams(PROJECT)).thenReturn(List.of(legalWorkstream));

        var mockFinding = mock(Finding.class);
        when(findingService.create(eq(PROJECT), any(), any())).thenReturn(mockFinding);

        var finding = service.instantiate(PROJECT, templateId, null);

        assertThat(finding).isEqualTo(mockFinding);
        verify(findingService).create(eq(PROJECT), any(Finding.Content.class), any(FindingService.Rating.class));
    }
}
