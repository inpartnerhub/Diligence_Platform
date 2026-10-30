package com.inpartnerhub.diligence.request.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RequestItemDocumentRepository extends JpaRepository<RequestItemDocument, RequestItemDocument.Key> {

    List<RequestItemDocument> findByRequestItemIdOrderByLinkedAt(UUID requestItemId);

    boolean existsByRequestItemId(UUID requestItemId);

    long countByRequestItemId(UUID requestItemId);

    @Query("select d.requestItemId, count(d) from RequestItemDocument d where d.projectId = :projectId "
            + "group by d.requestItemId")
    List<Object[]> countsByItem(UUID projectId);
}
