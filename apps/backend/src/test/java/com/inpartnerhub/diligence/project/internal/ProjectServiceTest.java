package com.inpartnerhub.diligence.project.internal;

import static com.inpartnerhub.diligence.project.internal.ProjectTestData.CLIENT_ORG;
import static com.inpartnerhub.diligence.project.internal.ProjectTestData.TARGET_ORG;
import static com.inpartnerhub.diligence.project.internal.ProjectTestData.organization;
import static com.inpartnerhub.diligence.project.internal.ProjectTestData.project;
import static com.inpartnerhub.diligence.project.internal.ProjectTestData.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.admin.Directory.OrganizationType;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectCreated;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.ProjectStage;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;

class ProjectServiceTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final WorkstreamRepository workstreams = mock(WorkstreamRepository.class);
    private final ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    private final TemplateService templates = mock(TemplateService.class);
    private final ProjectAccess access = mock(ProjectAccess.class);
    private final Directory directory = mock(Directory.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ProjectService service = new ProjectService(projects, workstreams, members, templates, access,
            directory, currentUser, events);

    private final List<Workstream> savedWorkstreams = new ArrayList<>();
    private final List<ProjectMember> savedMembers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(directory.findOrganization(CLIENT_ORG)).thenReturn(organization(CLIENT_ORG, OrganizationType.CLIENT));
        when(directory.findOrganization(TARGET_ORG)).thenReturn(organization(TARGET_ORG, OrganizationType.TARGET));
        when(projects.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workstreams.save(any())).thenAnswer(inv -> {
            savedWorkstreams.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(members.save(any())).thenAnswer(inv -> {
            savedMembers.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    @Test
    void creatorBecomesProjectManagerAndWorkstreamsAreCreated() {
        var pm = user(Role.PROJECT_MANAGER);
        when(currentUser.requireCurrentUser()).thenReturn(pm);

        var project = service.create(command(" dd-2026-007 ", null, Set.of(WorkstreamType.LEGAL, WorkstreamType.TAX)));

        assertThat(project.getCode()).isEqualTo("DD-2026-007");
        assertThat(project.getStage()).isEqualTo(ProjectStage.INITIATION);
        assertThat(savedMembers).singleElement().satisfies(m -> {
            assertThat(m.getUserId()).isEqualTo(pm.id());
            assertThat(m.getRole()).isEqualTo(ProjectRole.PROJECT_MANAGER);
        });
        assertThat(savedWorkstreams).extracting(Workstream::getType)
                .containsExactlyInAnyOrder(WorkstreamType.LEGAL, WorkstreamType.TAX);
        verify(events).publishEvent(any(ProjectCreated.class));
    }

    @Test
    void partnersStartAsPartnerAndTemplatesProvideWorkstreams() {
        when(currentUser.requireCurrentUser()).thenReturn(user(Role.PARTNER));
        var templateId = UUID.randomUUID();
        when(templates.definition(templateId)).thenReturn(new TemplateDefinition(
                List.of(WorkstreamType.FINANCIAL, WorkstreamType.HR), null, null, null));

        var project = service.create(command("DD-1", templateId, null));

        assertThat(project.getTemplateId()).isEqualTo(templateId);
        assertThat(savedMembers.getFirst().getRole()).isEqualTo(ProjectRole.PARTNER);
        assertThat(savedWorkstreams).extracting(Workstream::getType)
                .containsExactlyInAnyOrder(WorkstreamType.FINANCIAL, WorkstreamType.HR);
    }

    @Test
    void validatesOrganizationsDatesAndWorkstreams() {
        when(currentUser.requireCurrentUser()).thenReturn(user(Role.PROJECT_MANAGER));

        assertThatThrownBy(() -> service.create(command("DD-1", null, Set.of())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("workstream");

        var swapped = new ProjectService.CreateCommand("DD-1", "P", TARGET_ORG, CLIENT_ORG, null, null, null,
                Set.of(WorkstreamType.LEGAL));
        assertThatThrownBy(() -> service.create(swapped)).hasMessageContaining("client organization");

        var badDates = new ProjectService.CreateCommand("DD-1", "P", CLIENT_ORG, TARGET_ORG,
                LocalDate.of(2027, 1, 1), LocalDate.of(2026, 1, 1), null, Set.of(WorkstreamType.LEGAL));
        assertThatThrownBy(() -> service.create(badDates)).hasMessageContaining("End date");
    }

    @Test
    void archivingRevokesExternalAccessOnly() {
        var project = project(UUID.randomUUID());
        var client = new ProjectMember(project.getId(), UUID.randomUUID(), ProjectRole.CLIENT, null, null);
        var target = new ProjectMember(project.getId(), UUID.randomUUID(), ProjectRole.TARGET, null, null);
        var analyst = new ProjectMember(project.getId(), UUID.randomUUID(), ProjectRole.ANALYST, null, null);
        when(access.manageable(project.getId())).thenReturn(project);
        when(members.findByProjectIdOrderByCreatedAt(project.getId())).thenReturn(List.of(client, target, analyst));

        service.archive(project.getId());

        assertThat(project.isArchived()).isTrue();
        assertThat(project.getStage()).isEqualTo(ProjectStage.CLOSED);
        assertThat(client.getAccessExpiresAt()).isNotNull();
        assertThat(target.getAccessExpiresAt()).isNotNull();
        assertThat(analyst.getAccessExpiresAt()).isNull();

        service.reactivate(project.getId());
        assertThat(project.isArchived()).isFalse();
    }

    @Test
    void updatesAndReplacesWorkstreams() {
        var project = project(UUID.randomUUID());
        when(access.editable(project.getId())).thenReturn(project);
        var legal = new Workstream(project.getId(), WorkstreamType.LEGAL);
        var tax = new Workstream(project.getId(), WorkstreamType.TAX);
        when(workstreams.findByProjectIdOrderByType(project.getId())).thenReturn(List.of(legal, tax));

        service.update(project.getId(), "Atlas II", null, null, ProjectStage.DATA_COLLECTION);
        service.replaceWorkstreams(project.getId(), Set.of(WorkstreamType.LEGAL, WorkstreamType.HR));

        assertThat(project.getName()).isEqualTo("Atlas II");
        assertThat(project.getStage()).isEqualTo(ProjectStage.DATA_COLLECTION);
        verify(workstreams).delete(tax);
        assertThat(savedWorkstreams).extracting(Workstream::getType).containsExactly(WorkstreamType.HR);
        assertThatThrownBy(() -> service.replaceWorkstreams(project.getId(), Set.of()))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsWithFiltersAndReadsWorkstreams() {
        when(projects.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));
        assertThat(service.list(new ProjectService.ProjectFilter(Project.Status.ACTIVE, "atlas"),
                PageRequest.of(0, 20))).isEmpty();

        var project = project(UUID.randomUUID());
        when(access.visible(project.getId())).thenReturn(project);
        when(workstreams.findByProjectIdOrderByType(project.getId())).thenReturn(List.of());
        assertThat(service.get(project.getId())).isSameAs(project);
        assertThat(service.workstreams(project.getId())).isEmpty();
    }

    @Test
    void archivedProjectsCannotBeEdited() {
        var project = project(UUID.randomUUID());
        project.archive(java.time.Instant.now());
        when(access.manageable(project.getId())).thenReturn(project);

        assertThat(service.archive(project.getId())).isSameAs(project); // idempotent
        var realAccess = new ProjectAccess(projects, members, currentUser);
        when(projects.findById(project.getId())).thenReturn(Optional.of(project));
        when(currentUser.requireCurrentUser()).thenReturn(new com.inpartnerhub.diligence.security.AuthenticatedUser(
                project.getCreatedBy(), "c", "c@x.test", "C", Set.of(Role.PROJECT_MANAGER)));
        assertThatThrownBy(() -> realAccess.editable(project.getId())).isInstanceOf(ResourceConflictException.class);
    }

    private ProjectService.CreateCommand command(String code, UUID templateId, Set<WorkstreamType> types) {
        return new ProjectService.CreateCommand(code, "Project Atlas", CLIENT_ORG, TARGET_ORG,
                LocalDate.of(2026, 11, 1), LocalDate.of(2027, 2, 28), templateId, types);
    }
}
