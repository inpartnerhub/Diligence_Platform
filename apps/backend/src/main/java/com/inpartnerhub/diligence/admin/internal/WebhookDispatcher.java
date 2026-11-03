package com.inpartnerhub.diligence.admin.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;

/**
 * Spring Modulith event listener dispatching real-time outbound webhooks for domain platform events (PRD ADM-07).
 */
@Component
class WebhookDispatcher {

    private final WebhookService webhookService;

    WebhookDispatcher(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @ApplicationModuleListener
    void on(AuditableEvent event) {
        webhookService.dispatch(event);
    }
}

