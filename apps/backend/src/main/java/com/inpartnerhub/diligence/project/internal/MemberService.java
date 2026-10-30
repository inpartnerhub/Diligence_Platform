package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.Directory.UserSummary;
import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectMemberAdded;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectMemberRemoved;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/** Project team (PRD PRJ-02): members with a project role, optionally scoped to one workstream. */
@Service
@Transactional
class MemberService {

    record AddCommand(UUID userId, ProjectRole role, UUID workstreamId, Instant accessExpiresAt) {
    }

    private final ProjectMemberRepository members;
    private final WorkstreamRepository workstreams;
    private final ProjectAccess access;
    private final Directory directory;
    private final ApplicationEventPublisher events;

    MemberService(ProjectMemberRepository members, WorkstreamRepository workstreams, ProjectAccess access,
            Directory directory, ApplicationEventPublisher events) {
        this.members = members;
        this.workstreams = workstreams;
        this.access = access;
        this.directory = directory;
        this.events = events;
    }

    @Transactional(readOnly = true)
    List<ProjectMember> list(UUID projectId) {
        access.visible(projectId);
        return members.findByProjectIdOrderByCreatedAt(projectId);
    }

    ProjectMember add(UUID projectId, AddCommand command) {
        var project = access.editable(projectId);
        var user = directory.findUser(command.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User", command.userId()));
        checkEligible(project, user, command.role());
        if (command.workstreamId() != null && workstreams.findById(command.workstreamId())
                .filter(w -> w.getProjectId().equals(projectId)).isEmpty()) {
            throw new InvalidRequestException("Workstream does not belong to this project");
        }
        if (command.accessExpiresAt() != null && !command.accessExpiresAt().isAfter(Instant.now())) {
            throw new InvalidRequestException("Access expiry must be in the future");
        }
        var duplicate = members.findByProjectIdAndUserId(projectId, user.id()).stream()
                .anyMatch(m -> Objects.equals(m.getWorkstreamId(), command.workstreamId()));
        if (duplicate) {
            throw new ResourceConflictException(user.displayName() + " is already a member with this scope");
        }
        var member = members.save(new ProjectMember(projectId, user.id(), command.role(), command.workstreamId(),
                command.accessExpiresAt()));
        events.publishEvent(new ProjectMemberAdded(projectId, member.getId(), user.id(), command.role(),
                RequestMetadata.capture()));
        return member;
    }

    void remove(UUID projectId, UUID memberId) {
        access.editable(projectId);
        var member = members.findById(memberId).filter(m -> m.getProjectId().equals(projectId))
                .orElseThrow(() -> new ResourceNotFoundException("Project member", memberId));
        if (ProjectRole.MANAGERS.contains(member.getRole())) {
            var now = Instant.now();
            var otherManagers = members.findByProjectIdOrderByCreatedAt(projectId).stream()
                    .filter(m -> !m.getId().equals(memberId))
                    .filter(m -> m.isActiveAt(now) && ProjectRole.MANAGERS.contains(m.getRole()))
                    .count();
            if (otherManagers == 0) {
                throw new ResourceConflictException("A project needs at least one partner or project manager");
            }
        }
        members.delete(member);
        events.publishEvent(new ProjectMemberRemoved(projectId, memberId, member.getUserId(),
                RequestMetadata.capture()));
    }

    /**
     * Membership rules (PRD Bab 3): the project role must fit the user's global role, and client/target users can
     * only join projects for their own organization.
     */
    static void checkEligible(Project project, UserSummary user, ProjectRole role) {
        if (!user.active()) {
            throw new InvalidRequestException(user.displayName() + " is disabled");
        }
        if (!ProjectRole.allowedFor(user.role()).contains(role)) {
            throw new InvalidRequestException("A user with global role " + user.role() + " cannot be " + role
                    + " in a project");
        }
        if (role == ProjectRole.CLIENT && !project.getClientOrganizationId().equals(user.organizationId())) {
            throw new InvalidRequestException(user.displayName() + " does not belong to the project's client");
        }
        if (role == ProjectRole.TARGET && !project.getTargetOrganizationId().equals(user.organizationId())) {
            throw new InvalidRequestException(user.displayName() + " does not belong to the project's target");
        }
    }
}
