package com.inpartnerhub.diligence.project.internal;

import static com.inpartnerhub.diligence.project.internal.ProjectTestData.CLIENT_ORG;
import static com.inpartnerhub.diligence.project.internal.ProjectTestData.TARGET_ORG;
import static com.inpartnerhub.diligence.project.internal.ProjectTestData.project;
import static com.inpartnerhub.diligence.project.internal.ProjectTestData.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.admin.Directory.UserSummary;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectMemberAdded;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectMemberRemoved;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

class MemberServiceTest {

    private final ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    private final WorkstreamRepository workstreams = mock(WorkstreamRepository.class);
    private final ProjectAccess access = mock(ProjectAccess.class);
    private final Directory directory = mock(Directory.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final MemberService service = new MemberService(members, workstreams, access, directory, events);

    private final Project project = project(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        when(access.editable(project.getId())).thenReturn(project);
        when(access.visible(project.getId())).thenReturn(project);
        when(members.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void externalUsersOnlyJoinTheirOwnSide() {
        assertThatNoException().isThrownBy(() ->
                MemberService.checkEligible(project, summary(Role.CLIENT, CLIENT_ORG), ProjectRole.CLIENT));
        assertThatNoException().isThrownBy(() ->
                MemberService.checkEligible(project, summary(Role.TARGET, TARGET_ORG), ProjectRole.TARGET));

        assertThatThrownBy(() -> MemberService.checkEligible(project, summary(Role.TARGET, CLIENT_ORG),
                ProjectRole.TARGET)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("target");
        assertThatThrownBy(() -> MemberService.checkEligible(project, summary(Role.CLIENT, TARGET_ORG),
                ProjectRole.CLIENT)).hasMessageContaining("client");
        assertThatThrownBy(() -> MemberService.checkEligible(project, summary(Role.TARGET, TARGET_ORG),
                ProjectRole.ANALYST)).hasMessageContaining("cannot be ANALYST");
        assertThatThrownBy(() -> MemberService.checkEligible(project, summary(Role.SUPER_ADMIN, null),
                ProjectRole.OBSERVER)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void internalUsersMayTakeAnyInternalRole() {
        for (var role : List.of(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER, ProjectRole.ANALYST,
                ProjectRole.OBSERVER)) {
            assertThatNoException().isThrownBy(() ->
                    MemberService.checkEligible(project, summary(Role.ANALYST, null), role));
        }
        assertThatThrownBy(() -> MemberService.checkEligible(project, summary(Role.ANALYST, null),
                ProjectRole.CLIENT)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void disabledUsersCannotJoin() {
        var disabled = new UserSummary(UUID.randomUUID(), "d@x.test", "D", null, Role.ANALYST, false);
        assertThatThrownBy(() -> MemberService.checkEligible(project, disabled, ProjectRole.ANALYST))
                .hasMessageContaining("disabled");
    }

    @Test
    void addsMemberScopedToWorkstream() {
        var analyst = summary(Role.ANALYST, null);
        var legal = new Workstream(project.getId(), WorkstreamType.LEGAL);
        when(directory.findUser(analyst.id())).thenReturn(Optional.of(analyst));
        when(workstreams.findById(legal.getId())).thenReturn(Optional.of(legal));

        var member = service.add(project.getId(), new MemberService.AddCommand(analyst.id(), ProjectRole.ANALYST,
                legal.getId(), null));

        assertThat(member.getWorkstreamId()).isEqualTo(legal.getId());
        verify(events).publishEvent(any(ProjectMemberAdded.class));
    }

    @Test
    void rejectsForeignWorkstreamPastExpiryAndDuplicates() {
        var analyst = summary(Role.ANALYST, null);
        when(directory.findUser(analyst.id())).thenReturn(Optional.of(analyst));
        var foreign = new Workstream(UUID.randomUUID(), WorkstreamType.TAX);
        when(workstreams.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.add(project.getId(), new MemberService.AddCommand(analyst.id(),
                ProjectRole.ANALYST, foreign.getId(), null))).hasMessageContaining("Workstream");
        assertThatThrownBy(() -> service.add(project.getId(), new MemberService.AddCommand(analyst.id(),
                ProjectRole.ANALYST, null, Instant.now().minusSeconds(60)))).hasMessageContaining("future");

        when(members.findByProjectIdAndUserId(project.getId(), analyst.id())).thenReturn(
                List.of(new ProjectMember(project.getId(), analyst.id(), ProjectRole.ANALYST, null, null)));
        assertThatThrownBy(() -> service.add(project.getId(), new MemberService.AddCommand(analyst.id(),
                ProjectRole.ANALYST, null, null))).isInstanceOf(ResourceConflictException.class);
        verify(members, never()).save(any());
    }

    @Test
    void unknownUserIsNotFound() {
        var id = UUID.randomUUID();
        when(directory.findUser(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(project.getId(), new MemberService.AddCommand(id, ProjectRole.ANALYST,
                null, null))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void keepsAtLeastOneManager() {
        var manager = new ProjectMember(project.getId(), UUID.randomUUID(), ProjectRole.PROJECT_MANAGER, null, null);
        var analyst = new ProjectMember(project.getId(), UUID.randomUUID(), ProjectRole.ANALYST, null, null);
        when(members.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(members.findById(analyst.getId())).thenReturn(Optional.of(analyst));
        when(members.findByProjectIdOrderByCreatedAt(project.getId())).thenReturn(List.of(manager, analyst));

        assertThatThrownBy(() -> service.remove(project.getId(), manager.getId()))
                .isInstanceOf(ResourceConflictException.class);

        service.remove(project.getId(), analyst.getId());
        verify(members).delete(analyst);
        verify(events).publishEvent(any(ProjectMemberRemoved.class));
    }

    @Test
    void removingAManagerIsFineWhenAnotherRemains() {
        var first = new ProjectMember(project.getId(), UUID.randomUUID(), ProjectRole.PROJECT_MANAGER, null, null);
        var partner = new ProjectMember(project.getId(), UUID.randomUUID(), ProjectRole.PARTNER, null, null);
        when(members.findById(first.getId())).thenReturn(Optional.of(first));
        when(members.findByProjectIdOrderByCreatedAt(project.getId())).thenReturn(List.of(first, partner));

        service.remove(project.getId(), first.getId());

        verify(members).delete(first);
        assertThat(service.list(project.getId())).containsExactly(first, partner);
    }

    @Test
    void memberOfAnotherProjectIsNotFound() {
        var other = new ProjectMember(UUID.randomUUID(), UUID.randomUUID(), ProjectRole.ANALYST, null, null);
        when(members.findById(other.getId())).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.remove(project.getId(), other.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
