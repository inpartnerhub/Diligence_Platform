package com.inpartnerhub.diligence.project.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface WorkstreamRepository extends JpaRepository<Workstream, UUID> {

    List<Workstream> findByProjectIdOrderByType(UUID projectId);
}
