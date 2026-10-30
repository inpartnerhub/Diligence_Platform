package com.inpartnerhub.diligence.project.internal;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectTemplateRepository extends JpaRepository<ProjectTemplate, UUID> {

    boolean existsByNameIgnoreCase(String name);
}
