package com.inpartnerhub.diligence.project.internal;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Row-level security limits every query to projects the caller may see. */
interface ProjectRepository extends JpaRepository<Project, UUID>, JpaSpecificationExecutor<Project> {
}
