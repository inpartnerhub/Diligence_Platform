package com.inpartnerhub.diligence.request.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface RequestStatusChangeRepository extends JpaRepository<RequestStatusChange, UUID> {

    List<RequestStatusChange> findByRequestItemIdOrderByChangedAt(UUID requestItemId);
}
