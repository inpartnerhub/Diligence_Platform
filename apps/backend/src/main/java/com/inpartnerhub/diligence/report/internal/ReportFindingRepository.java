package com.inpartnerhub.diligence.report.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ReportFindingRepository extends JpaRepository<ReportFinding, ReportFinding.Key> {

    /** The findings a report version was generated from, in report order. */
    List<ReportFinding> findByReportIdOrderByPositionAsc(UUID reportId);
}
