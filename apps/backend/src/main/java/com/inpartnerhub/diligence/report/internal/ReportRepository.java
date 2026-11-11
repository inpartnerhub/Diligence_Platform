package com.inpartnerhub.diligence.report.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReportRepository extends JpaRepository<Report, UUID> {

    List<Report> findByProjectIdOrderByVersionNumberDesc(UUID projectId);

    @Query("select coalesce(max(r.versionNumber), 0) from Report r where r.projectId = :projectId")
    int lastVersion(UUID projectId);

    /** Project purge (ADM-06, #114): child rows of this module go with it through ON DELETE CASCADE. */
    @Modifying
    @Query("delete from Report e where e.projectId = :projectId")
    int deleteAllForProject(@Param("projectId") UUID projectId);
}
