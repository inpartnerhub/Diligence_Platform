package com.inpartnerhub.diligence.admin.internal;

import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;

/**
 * Spring Modulith event listener dispatching real-time outbound webhooks for domain platform events (PRD ADM-07).
 * It runs outside a transaction ({@code NOT_SUPPORTED}): deliveries wait on remote endpoints and must not hold a
 * pooled database connection meanwhile. When a project's data is purged, the delivery logs that mention it are
 * deleted after the purge event itself was delivered (#90).
 */
@Component
class WebhookDispatcher {

    private final WebhookService webhookService;
    private final WebhookLogRetention retention;

    WebhookDispatcher(WebhookService webhookService, WebhookLogRetention retention) {
        this.webhookService = webhookService;
        this.retention = retention;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(AuditableEvent event) {
        webhookService.dispatch(event);
        if (WebhookLogRetention.PROJECT_PURGED.equals(event.auditAction())
                && "project".equals(event.auditObjectType())) {
            retention.deleteForPurgedProject(UUID.fromString(event.auditObjectId()));
        }
    }
}

