package com.inpartnerhub.diligence.project.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    List<ProjectMember> findByProjectIdOrderByCreatedAt(UUID projectId);

    List<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    long countByProjectId(UUID projectId);
}
