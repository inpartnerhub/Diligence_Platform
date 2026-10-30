package com.inpartnerhub.diligence.finding.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface FindingRepository extends JpaRepository<Finding, UUID> {

    List<Finding> findByProjectIdOrderByNumber(UUID projectId);

    @Query("select f.number from Finding f where f.projectId = :projectId and f.number like :prefix%")
    List<String> numbersWithPrefix(UUID projectId, String prefix);
}
