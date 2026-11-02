package com.inpartnerhub.diligence.project.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Row-level security limits every query to projects the caller may see. */
interface ProjectRepository extends JpaRepository<Project, UUID>, JpaSpecificationExecutor<Project> {

    @Query("select p from Project p where p.status = 'ARCHIVED' and p.purgedAt is null and p.archivedAt is not null")
    List<Project> findArchivedCandidates();
}
