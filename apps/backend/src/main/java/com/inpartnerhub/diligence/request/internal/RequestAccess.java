package com.inpartnerhub.diligence.request.internal;

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
 * Application-level request-list authorization on top of row-level security (which already hides unreleased items
 * from the target): the client side has no request list (it follows progress on the dashboard), analysts and
 * workstream-scoped target users only see their workstreams, observers are read-only and archived projects too.
 */
@Component
class RequestAccess {

    private final ProjectDirectory projects;
    private final RequestItemRepository items;

    RequestAccess(ProjectDirectory projects, RequestItemRepository items) {
        this.projects = projects;
        this.items = items;
    }

    ProjectContext project(UUID projectId) {
        var context = projects.context(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        if (!context.isInternal() && !context.hasAnyRole(ProjectRole.TARGET)) {
            throw new AccessDeniedException("The request list is not available to the client side");
        }
        return context;
    }

    /** Partners, project managers and analysts maintain the list of an active project. */
    ProjectContext editableProject(UUID projectId) {
        var context = writable(project(projectId));
        if (!isEditor(context)) {
            throw new AccessDeniedException("Only the internal team maintains the request list");
        }
        return context;
    }

    /** Releasing items to the target is a project-manager decision (PRD REQ-06). */
    ProjectContext manageableProject(UUID projectId) {
        var context = writable(project(projectId));
        if (!context.canManage()) {
            throw new AccessDeniedException("Only the project's partners and project managers release requests");
        }
        return context;
    }

    ProjectContext writableProject(UUID projectId) {
        return writable(project(projectId));
    }

    RequestItem item(ProjectContext context, UUID itemId) {
        return items.findById(itemId)
                .filter(i -> i.getProjectId().equals(context.projectId()))
                .filter(i -> context.coversWorkstream(i.getWorkstreamId()))
                .orElseThrow(() -> new ResourceNotFoundException("Request item", itemId));
    }

    /** The project's workstreams by id; a workstream outside the caller's scope is rejected. */
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

    /** External target users; a consultant who is also listed as target still acts as consultant. */
    static boolean isTarget(ProjectContext context) {
        return !context.isInternal() && context.hasAnyRole(ProjectRole.TARGET);
    }

    private static ProjectContext writable(ProjectContext context) {
        if (context.archived()) {
            throw new ResourceConflictException("Project " + context.code() + " is archived");
        }
        return context;
    }
}
