package com.inpartnerhub.diligence.request.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface RequestCommentRepository extends JpaRepository<RequestComment, UUID> {

    List<RequestComment> findByRequestItemIdOrderByCreatedAt(UUID requestItemId);
}
