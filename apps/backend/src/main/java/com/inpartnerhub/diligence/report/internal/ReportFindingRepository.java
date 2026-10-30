package com.inpartnerhub.diligence.report.internal;

import org.springframework.data.jpa.repository.JpaRepository;

interface ReportFindingRepository extends JpaRepository<ReportFinding, ReportFinding.Key> {
}
