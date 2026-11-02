package com.inpartnerhub.diligence.dataroom.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentAnnotationRepository extends JpaRepository<DocumentAnnotation, UUID> {

    List<DocumentAnnotation> findByProjectIdAndDocumentIdOrderByPageNumberAscCreatedAtAsc(UUID projectId,
            UUID documentId);

    List<DocumentAnnotation> findByProjectIdAndFindingIdOrderByCreatedAtAsc(UUID projectId, UUID findingId);

    List<DocumentAnnotation> findByProjectIdAndDocumentIdAndPageNumberOrderByCreatedAtAsc(UUID projectId,
            UUID documentId, int pageNumber);
}
