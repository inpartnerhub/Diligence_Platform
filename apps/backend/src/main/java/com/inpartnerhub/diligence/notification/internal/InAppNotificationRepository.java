package com.inpartnerhub.diligence.notification.internal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InAppNotificationRepository extends JpaRepository<InAppNotification, UUID> {

    Page<InAppNotification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<InAppNotification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying
    @Query("update InAppNotification n set n.readAt = :at where n.userId = :userId and n.readAt is null")
    int markAllRead(UUID userId, Instant at);

    /** Project purge (ADM-06, #114): child rows of this module go with it through ON DELETE CASCADE. */
    @Modifying
    @Query("delete from InAppNotification e where e.projectId = :projectId")
    int deleteAllForProject(@Param("projectId") UUID projectId);
}
