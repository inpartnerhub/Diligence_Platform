package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.security.CurrentUserProvider;

@Service
@Transactional(readOnly = true)
class ProjectDirectoryService implements ProjectDirectory {

    private final ProjectRepository projects;
    private final WorkstreamRepository workstreams;
    private final ProjectMemberRepository members;
    private final ProjectAccess access;
    private final TemplateService templates;
    private final CurrentUserProvider currentUser;

    ProjectDirectoryService(ProjectRepository projects, WorkstreamRepository workstreams,
            ProjectMemberRepository members, ProjectAccess access, TemplateService templates,
            CurrentUserProvider currentUser) {
        this.projects = projects;
        this.workstreams = workstreams;
        this.members = members;
        this.access = access;
        this.templates = templates;
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

    @Override
    public Optional<ProjectSummary> summary(UUID projectId) {
        return projects.findById(projectId)
                .map(p -> new ProjectSummary(p.getId(), p.getCode(), p.getName(), p.getTargetOrganizationId(),
                        p.isArchived()));
    }

    @Override
    public List<WorkstreamSummary> workstreams(UUID projectId) {
        return workstreams.findByProjectIdOrderByType(projectId).stream()
                .map(w -> new WorkstreamSummary(w.getId(), w.getType())).toList();
    }

    @Override
    public List<FindingCategory> findingCategories(UUID projectId) {
        var project = projects.findById(projectId).orElse(null);
        if (project == null || project.getTemplateId() == null) {
            return List.of();
        }
        var byType = workstreams(projectId).stream()
                .collect(Collectors.toMap(WorkstreamSummary::type, WorkstreamSummary::id));
        return templates.definition(project.getTemplateId()).findingCategories().stream()
                .filter(c -> c.name() != null && !c.name().isBlank() && byType.containsKey(c.workstream()))
                .map(c -> new FindingCategory(byType.get(c.workstream()), c.workstream(), c.name().strip()))
                .toList();
    }

    @Override
    public List<MemberSummary> members(UUID projectId) {
        var now = Instant.now();
        return members.findByProjectIdOrderByCreatedAt(projectId).stream()
                .filter(m -> m.isActiveAt(now))
                .map(m -> new MemberSummary(m.getUserId(), m.getRole(), m.getWorkstreamId()))
                .toList();
    }
}
