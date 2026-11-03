package com.inpartnerhub.diligence.admin.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLog, UUID> {

    List<WebhookDeliveryLog> findBySubscriptionIdOrderByDeliveredAtDesc(UUID subscriptionId, Pageable pageable);

    List<WebhookDeliveryLog> findTop25BySubscriptionIdOrderByDeliveredAtDesc(UUID subscriptionId);
}

