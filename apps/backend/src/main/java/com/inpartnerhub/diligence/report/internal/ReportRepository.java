package com.inpartnerhub.diligence.report.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ReportRepository extends JpaRepository<Report, UUID> {

    List<Report> findByProjectIdOrderByVersionNumberDesc(UUID projectId);

    @Query("select coalesce(max(r.versionNumber), 0) from Report r where r.projectId = :projectId")
    int lastVersion(UUID projectId);
}
