package com.inpartnerhub.diligence.request.internal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RequestItemRepository extends JpaRepository<RequestItem, UUID> {

    List<RequestItem> findByProjectIdOrderByNumber(UUID projectId);

    Optional<RequestItem> findByProjectIdAndNumber(UUID projectId, String number);

    List<RequestItem> findByProjectIdAndStatusAndWave(UUID projectId, RequestStatus status, short wave);

    @Query("select i.number from RequestItem i where i.projectId = :projectId and i.number like :prefix%")
    List<String> numbersWithPrefix(UUID projectId, String prefix);

    /** Open items due on or before {@code until} that were not reminded on {@code today} yet (REQ-04). */
    @Query("""
            select i from RequestItem i
            where i.status in :statuses and i.dueDate is not null and i.dueDate <= :until
              and (i.lastRemindedAt is null or i.lastRemindedAt < :today)
            order by i.projectId, i.dueDate
            """)
    List<RequestItem> dueForReminder(Collection<RequestStatus> statuses, LocalDate until, LocalDate today);
}
