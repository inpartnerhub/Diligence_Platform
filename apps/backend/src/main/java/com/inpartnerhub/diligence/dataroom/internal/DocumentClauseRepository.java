package com.inpartnerhub.diligence.dataroom.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DocumentClauseRepository extends JpaRepository<DocumentClause, UUID> {

    List<DocumentClause> findByProjectIdAndDocumentIdOrderByPageNumberAsc(UUID projectId, UUID documentId);

    List<DocumentClause> findByProjectIdAndClauseTypeOrderByConfidenceDesc(UUID projectId, String clauseType);

    List<DocumentClause> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    @Modifying
    @Query("delete from DocumentClause c where c.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);
}
