package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.security.CurrentUserProvider;

@Service
@Transactional(readOnly = true)
class ProjectDirectoryService implements ProjectDirectory {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ProjectAccess access;
    private final CurrentUserProvider currentUser;

    ProjectDirectoryService(ProjectRepository projects, ProjectMemberRepository members, ProjectAccess access,
            CurrentUserProvider currentUser) {
        this.projects = projects;
        this.members = members;
        this.access = access;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<ProjectContext> context(UUID projectId) {
        return projects.findById(projectId).map(project -> {
            var userId = currentUser.requireCurrentUser().id();
            var now = Instant.now();
            var memberships = members.findByProjectIdAndUserId(projectId, userId).stream()
                    .filter(m -> m.isActiveAt(now))
                    .toList();
            var workstreams = new HashSet<UUID>();
            if (memberships.stream().noneMatch(m -> m.getWorkstreamId() == null)) {
                memberships.forEach(m -> workstreams.add(m.getWorkstreamId()));
            }
            return new ProjectContext(project.getId(), project.getCode(), project.isArchived(),
                    access.rolesOf(project), workstreams);
        });
    }
}
