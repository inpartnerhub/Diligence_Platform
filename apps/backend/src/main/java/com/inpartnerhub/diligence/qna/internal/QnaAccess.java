package com.inpartnerhub.diligence.qna.internal;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectDirectory.WorkstreamSummary;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/**
 * Application-level Q&A authorization on top of row-level security (which limits the target to sent questions and
 * the client to its own): workstream scope for analysts and scoped target users, read-only observers and archived
 * projects.
 */
@Component
class QnaAccess {

    private final ProjectDirectory projects;
    private final QuestionRepository questions;

    QnaAccess(ProjectDirectory projects, QuestionRepository questions) {
        this.projects = projects;
        this.questions = questions;
    }

    ProjectContext project(UUID projectId) {
        return projects.context(projectId).orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }

    ProjectContext writableProject(UUID projectId) {
        var context = project(projectId);
        if (context.archived()) {
            throw new ResourceConflictException("Project " + context.code() + " is archived");
        }
        return context;
    }

    Question question(ProjectContext context, UUID questionId) {
        return questions.findById(questionId)
                .filter(q -> q.getProjectId().equals(context.projectId()))
                .filter(q -> isClient(context) || context.coversWorkstream(q.getWorkstreamId()))
                .orElseThrow(() -> new ResourceNotFoundException("Question", questionId));
    }

    void requireWorkstream(ProjectContext context, UUID workstreamId) {
        if (workstreamId == null) {
            return;
        }
        var known = projects.workstreams(context.projectId()).stream().map(WorkstreamSummary::id)
                .collect(Collectors.toSet());
        if (!known.contains(workstreamId)) {
            throw new InvalidRequestException("Unknown workstream for this project");
        }
        if (isEditor(context) && !context.coversWorkstream(workstreamId)) {
            throw new AccessDeniedException("This workstream is outside your assignment");
        }
    }

    Set<UUID> workstreams(UUID projectId) {
        return projects.workstreams(projectId).stream().map(WorkstreamSummary::id).collect(Collectors.toSet());
    }

    static boolean isEditor(ProjectContext context) {
        return context.hasAnyRole(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER, ProjectRole.ANALYST);
    }

    static boolean isManager(ProjectContext context) {
        return context.canManage();
    }

    static boolean isTarget(ProjectContext context) {
        return !context.isInternal() && context.hasAnyRole(ProjectRole.TARGET);
    }

    static boolean isClient(ProjectContext context) {
        return !context.isInternal() && context.hasAnyRole(ProjectRole.CLIENT);
    }
}
