package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/**
 * Application-level project authorization. Row-level security already hides projects the caller is not part of
 * (they surface as 404); this adds role checks for actions within a visible project.
 */
@Component
class ProjectAccess {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final CurrentUserProvider currentUser;

    ProjectAccess(ProjectRepository projects, ProjectMemberRepository members, CurrentUserProvider currentUser) {
        this.projects = projects;
        this.members = members;
        this.currentUser = currentUser;
    }

    /** The project if the caller can see it; 404 otherwise (no information about existence leaks). */
    Project visible(UUID projectId) {
        return projects.findById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }

    /** Active roles of the caller in the project. The creator counts as project manager during bootstrap. */
    Set<ProjectRole> rolesOf(Project project) {
        var userId = currentUser.requireCurrentUser().id();
        var now = Instant.now();
        var roles = members.findByProjectIdAndUserId(project.getId(), userId).stream()
                .filter(m -> m.isActiveAt(now))
                .map(ProjectMember::getRole)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ProjectRole.class)));
        if (userId.equals(project.getCreatedBy())) {
            roles.add(ProjectRole.PROJECT_MANAGER);
        }
        return roles;
    }

    /** The project if the caller is one of its partners or project managers; 404/403 otherwise. */
    Project manageable(UUID projectId) {
        var project = visible(projectId);
        if (rolesOf(project).stream().noneMatch(ProjectRole.MANAGERS::contains)) {
            throw new AccessDeniedException("Only the project's partners and project managers can change it");
        }
        return project;
    }

    /** Like {@link #manageable(UUID)}, but also rejects archived projects. */
    Project editable(UUID projectId) {
        var project = manageable(projectId);
        if (project.isArchived()) {
            throw new ResourceConflictException("Project " + project.getCode() + " is archived");
        }
        return project;
    }
}
