package com.inpartnerhub.diligence.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLog, UUID> {

    List<WebhookDeliveryLog> findBySubscriptionIdOrderByDeliveredAtDesc(UUID subscriptionId, Pageable pageable);

    List<WebhookDeliveryLog> findTop25BySubscriptionIdOrderByDeliveredAtDesc(UUID subscriptionId);

    @Modifying
    @Query("delete from WebhookDeliveryLog l where l.deliveredAt < :cutoff")
    int deleteDeliveredBefore(@Param("cutoff") Instant cutoff);

    /** Logs whose payload mentions the id anywhere (object id, project id in the details). */
    @Modifying
    @Query(value = "DELETE FROM adm.webhook_delivery_log WHERE strpos(payload, :id) > 0", nativeQuery = true)
    int deleteMentioning(@Param("id") String id);
}

