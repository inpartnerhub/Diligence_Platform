package com.inpartnerhub.diligence.project;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The caller's view of a project, for modules that work inside projects (data room, request list, …). */
public interface ProjectDirectory {

    /** Empty when the caller cannot see the project (row-level security). */
    Optional<ProjectContext> context(UUID projectId);

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
