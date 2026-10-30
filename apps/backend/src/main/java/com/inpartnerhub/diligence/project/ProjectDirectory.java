package com.inpartnerhub.diligence.project;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The caller's view of a project, for modules that work inside projects (data room, request list, …). */
public interface ProjectDirectory {

    /** Empty when the caller cannot see the project (row-level security). */
    Optional<ProjectContext> context(UUID projectId);

    /** Code and state of a project visible in the current context; works without a user (background jobs). */
    Optional<ProjectSummary> summary(UUID projectId);

    /** The project's active workstreams visible to the caller, ordered by type. */
    List<WorkstreamSummary> workstreams(UUID projectId);

    /**
     * Active (not expired) memberships of a project. Row-level security applies: users only see the whole roster
     * when they belong to the internal team, so background jobs (system context) use it to find recipients.
     */
    List<MemberSummary> members(UUID projectId);

    record ProjectSummary(UUID id, String code, String name, boolean archived) {
    }

    record WorkstreamSummary(UUID id, WorkstreamType type) {
    }

    /** @param workstreamId  {@code null} when the membership covers every workstream */
    record MemberSummary(UUID userId, ProjectRole role, UUID workstreamId) {

        /** Whether this member works on the given workstream (null = not workstream-specific). */
        public boolean covers(UUID workstream) {
            return workstreamId == null || workstream == null || workstreamId.equals(workstream);
        }
    }

    /**
     * @param workstreams  workstreams the caller is limited to; empty when a membership covers all workstreams
     */
    record ProjectContext(UUID projectId, String code, boolean archived, Set<ProjectRole> roles,
            Set<UUID> workstreams) {

        public ProjectContext {
            roles = Set.copyOf(roles);
            workstreams = Set.copyOf(workstreams);
        }

        public boolean canManage() {
            return roles.stream().anyMatch(ProjectRole.MANAGERS::contains);
        }

        public boolean hasAnyRole(ProjectRole... candidates) {
            return Set.of(candidates).stream().anyMatch(roles::contains);
        }

        /** Internal team members (partner, project manager, analyst, observer). */
        public boolean isInternal() {
            return hasAnyRole(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER, ProjectRole.ANALYST,
                    ProjectRole.OBSERVER);
        }

        /** Whether content of the given workstream (null = not workstream-specific) is in the caller's scope. */
        public boolean coversWorkstream(UUID workstreamId) {
            return workstreamId == null || workstreams.isEmpty() || canManage() || workstreams.contains(workstreamId);
        }
    }
}
