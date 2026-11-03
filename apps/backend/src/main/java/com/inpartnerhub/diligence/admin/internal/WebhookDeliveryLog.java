package com.inpartnerhub.diligence.admin.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "adm", name = "webhook_delivery_log")
class WebhookDeliveryLog {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "delivered_at", nullable = false)
    private Instant deliveredAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected WebhookDeliveryLog() {
    }

    WebhookDeliveryLog(UUID id, UUID subscriptionId, String eventType, String payload, Integer statusCode,
            boolean success, int attemptCount, String errorMessage, Instant deliveredAt, Long durationMs) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.eventType = eventType;
        this.payload = payload;
        this.statusCode = statusCode;
        this.success = success;
        this.attemptCount = attemptCount;
        this.errorMessage = errorMessage;
        this.deliveredAt = deliveredAt;
        this.durationMs = durationMs;
    }

    UUID getId() {
        return id;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    String getEventType() {
        return eventType;
    }

    String getPayload() {
        return payload;
    }

    Integer getStatusCode() {
        return statusCode;
    }

    boolean isSuccess() {
        return success;
    }

    int getAttemptCount() {
        return attemptCount;
    }

    String getErrorMessage() {
        return errorMessage;
    }

    Instant getDeliveredAt() {
        return deliveredAt;
    }

    Long getDurationMs() {
        return durationMs;
    }
}

