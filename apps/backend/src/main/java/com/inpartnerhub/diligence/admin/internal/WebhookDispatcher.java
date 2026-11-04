package com.inpartnerhub.diligence.admin.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;

/**
 * Spring Modulith event listener dispatching real-time outbound webhooks for domain platform events (PRD ADM-07).
 * It runs outside a transaction ({@code NOT_SUPPORTED}): deliveries wait on remote endpoints and must not hold a
 * pooled database connection meanwhile.
 */
@Component
class WebhookDispatcher {

    private final WebhookService webhookService;

    WebhookDispatcher(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(AuditableEvent event) {
        webhookService.dispatch(event);
    }
}

