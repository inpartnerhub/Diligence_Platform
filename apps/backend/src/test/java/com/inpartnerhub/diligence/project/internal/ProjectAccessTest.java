package com.inpartnerhub.diligence.project.internal;

import static com.inpartnerhub.diligence.project.internal.ProjectTestData.project;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

class ProjectAccessTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ProjectAccess access = new ProjectAccess(projects, members, currentUser);

    private final UUID callerId = UUID.randomUUID();
    private final Project project = project(UUID.randomUUID());

    @Test
    void invisibleProjectsAreNotFound() {
        when(projects.findById(project.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.visible(project.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void onlyActiveManagersCanManage() {
        when(projects.findById(project.getId())).thenReturn(Optional.of(project));
        when(currentUser.requireCurrentUser()).thenReturn(caller());
        var expiredManager = new ProjectMember(project.getId(), callerId, ProjectRole.PROJECT_MANAGER, null,
                Instant.now().minusSeconds(1));
        var analyst = new ProjectMember(project.getId(), callerId, ProjectRole.ANALYST, null, null);
        when(members.findByProjectIdAndUserId(project.getId(), callerId)).thenReturn(List.of(expiredManager, analyst));

        assertThat(access.rolesOf(project)).containsExactly(ProjectRole.ANALYST);
        assertThatThrownBy(() -> access.manageable(project.getId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void creatorManagesDuringBootstrap() {
        var own = project(callerId);
        when(projects.findById(own.getId())).thenReturn(Optional.of(own));
        when(currentUser.requireCurrentUser()).thenReturn(caller());
        when(members.findByProjectIdAndUserId(own.getId(), callerId)).thenReturn(List.of());

        assertThat(access.editable(own.getId())).isSameAs(own);
    }

    private AuthenticatedUser caller() {
        return new AuthenticatedUser(callerId, "c", "c@x.test", "C", Set.of(Role.PROJECT_MANAGER));
    }
}
