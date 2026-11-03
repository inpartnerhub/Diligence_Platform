package com.inpartnerhub.diligence.finding.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.inpartnerhub.diligence.project.WorkstreamType;

interface FindingLibraryTemplateRepository extends JpaRepository<FindingLibraryTemplate, UUID> {

    List<FindingLibraryTemplate> findByWorkstreamTypeOrderByCategoryAscTitleAsc(WorkstreamType workstreamType);

    List<FindingLibraryTemplate> findAllByOrderByWorkstreamTypeAscCategoryAscTitleAsc();
}
