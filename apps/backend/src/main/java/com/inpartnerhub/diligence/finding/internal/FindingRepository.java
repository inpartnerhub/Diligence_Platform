package com.inpartnerhub.diligence.finding.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FindingRepository extends JpaRepository<Finding, UUID>, JpaSpecificationExecutor<Finding> {

    List<Finding> findByProjectIdOrderByNumber(UUID projectId);

    @Query("select f.number from Finding f where f.projectId = :projectId and f.number like :prefix%")
    List<String> numbersWithPrefix(UUID projectId, String prefix);

    /** Project purge (ADM-06, #114): child rows of this module go with it through ON DELETE CASCADE. */
    @Modifying
    @Query("delete from Finding e where e.projectId = :projectId")
    int deleteAllForProject(@Param("projectId") UUID projectId);
}
