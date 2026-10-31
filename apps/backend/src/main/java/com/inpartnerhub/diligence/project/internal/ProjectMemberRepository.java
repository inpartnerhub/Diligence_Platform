package com.inpartnerhub.diligence.project.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.inpartnerhub.diligence.project.ProjectRole;

interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    List<ProjectMember> findByProjectIdOrderByCreatedAt(UUID projectId);

    List<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    long countByProjectId(UUID projectId);

    /**
     * Ends access for the given roles at the database's own clock, which row-level security compares against
     * (prj.has_project_role): an application clock running ahead of the database would otherwise leave access
     * open for the difference. Access that already ended earlier is left as it is.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ProjectMember m set m.accessExpiresAt = current_timestamp
            where m.projectId = :projectId and m.role in :roles
              and (m.accessExpiresAt is null or m.accessExpiresAt > current_timestamp)""")
    int expireAccess(UUID projectId, Collection<ProjectRole> roles);
}
