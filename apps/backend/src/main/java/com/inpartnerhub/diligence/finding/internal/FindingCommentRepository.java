package com.inpartnerhub.diligence.finding.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface FindingCommentRepository extends JpaRepository<FindingComment, UUID> {

    List<FindingComment> findByFindingIdOrderByCreatedAt(UUID findingId);
}
