package com.inpartnerhub.diligence.finding.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface FindingStatusChangeRepository extends JpaRepository<FindingStatusChange, UUID> {

    List<FindingStatusChange> findByFindingIdOrderByChangedAt(UUID findingId);
}
