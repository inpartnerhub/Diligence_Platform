package com.inpartnerhub.diligence.report.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ReportStatusHistoryRepository extends JpaRepository<ReportStatusHistory, UUID> {

    List<ReportStatusHistory> findByProjectIdAndReportIdOrderByChangedAtAsc(UUID projectId, UUID reportId);
}
