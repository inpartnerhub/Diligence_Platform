package com.inpartnerhub.diligence.admin.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findByActiveTrue();

    List<WebhookSubscription> findByOrganizationId(UUID organizationId);

    List<WebhookSubscription> findAllByOrderByCreatedAtDesc();
}

