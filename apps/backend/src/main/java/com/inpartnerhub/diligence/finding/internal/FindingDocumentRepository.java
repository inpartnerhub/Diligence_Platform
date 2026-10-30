package com.inpartnerhub.diligence.finding.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface FindingDocumentRepository extends JpaRepository<FindingDocument, FindingDocument.Key> {

    List<FindingDocument> findByFindingIdOrderByLinkedAt(UUID findingId);

    boolean existsByFindingId(UUID findingId);

    @Query("select d.findingId, count(d) from FindingDocument d where d.projectId = :projectId group by d.findingId")
    List<Object[]> countsByFinding(UUID projectId);
}
