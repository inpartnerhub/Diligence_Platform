package com.inpartnerhub.diligence.finding.internal;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectDirectory.WorkstreamSummary;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/**
 * Application-level finding authorization on top of row-level security (which hides all findings from the target
 * and unreleased ones from the client): the target side gets no finding API at all, clients and observers read only,
 * analysts are limited to their workstreams, archived projects are read-only.
 */
@Component
class FindingAccess {

    private final ProjectDirectory projects;
    private final FindingRepository findings;

    FindingAccess(ProjectDirectory projects, FindingRepository findings) {
        this.projects = projects;
        this.findings = findings;
    }

    ProjectContext project(UUID projectId) {
        var context = projects.context(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        if (!context.isInternal() && !context.hasAnyRole(ProjectRole.CLIENT)) {
            throw new AccessDeniedException("Findings are not available to the target side");
        }
        return context;
    }

    /** Partners, project managers and analysts of an active project. */
    ProjectContext editableProject(UUID projectId) {
        var context = project(projectId);
        if (context.archived()) {
            throw new ResourceConflictException("Project " + context.code() + " is archived");
        }
        if (!isEditor(context)) {
            throw new AccessDeniedException("Only the internal team works on findings");
        }
        return context;
    }

    Finding finding(ProjectContext context, UUID findingId) {
        return findings.findById(findingId)
                .filter(f -> f.getProjectId().equals(context.projectId()))
                .filter(f -> !isInternal(context) || context.coversWorkstream(f.getWorkstreamId()))
                .orElseThrow(() -> new ResourceNotFoundException("Finding", findingId));
    }

    WorkstreamType workstream(ProjectContext context, UUID workstreamId) {
        var type = workstreams(context.projectId()).get(workstreamId);
        if (type == null) {
            throw new InvalidRequestException("Unknown workstream for this project");
        }
        if (!context.coversWorkstream(workstreamId)) {
            throw new AccessDeniedException("This workstream is outside your assignment");
        }
        return type;
    }

    Map<UUID, WorkstreamType> workstreams(UUID projectId) {
        return projects.workstreams(projectId).stream()
                .collect(Collectors.toMap(WorkstreamSummary::id, WorkstreamSummary::type));
    }

    static boolean isEditor(ProjectContext context) {
        return context.hasAnyRole(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER, ProjectRole.ANALYST);
    }

    static boolean isReviewer(ProjectContext context) {
        return context.canManage();
    }

    static boolean isPartner(ProjectContext context) {
        return context.hasAnyRole(ProjectRole.PARTNER);
    }

    static boolean isInternal(ProjectContext context) {
        return context.isInternal();
    }
}
