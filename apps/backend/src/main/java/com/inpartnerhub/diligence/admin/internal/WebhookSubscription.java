package com.inpartnerhub.diligence.admin.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(schema = "adm", name = "webhook_subscription")
class WebhookSubscription {

    @Id
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "target_url", nullable = false)
    private String targetUrl;

    @Column(name = "signing_key", nullable = false)
    @Convert(converter = SigningKeyConverter.class) // encrypted at rest (#64)
    private String signingKey;

    @Column
    private String description;

    @Column(name = "subscribed_events", nullable = false)
    private String subscribedEvents;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected WebhookSubscription() {
    }

    WebhookSubscription(UUID id, UUID organizationId, String targetUrl, String signingKey, String description,
            String subscribedEvents, boolean active, UUID createdBy, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.targetUrl = targetUrl;
        this.signingKey = signingKey;
        this.description = description;
        this.subscribedEvents = subscribedEvents != null && !subscribedEvents.isBlank() ? subscribedEvents.strip() : "*";
        this.active = active;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    UUID getId() {
        return id;
    }

    UUID getOrganizationId() {
        return organizationId;
    }

    String getTargetUrl() {
        return targetUrl;
    }

    void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    String getSigningKey() {
        return signingKey;
    }

    void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }

    String getDescription() {
        return description;
    }

    void setDescription(String description) {
        this.description = description;
    }

    String getSubscribedEvents() {
        return subscribedEvents;
    }

    void setSubscribedEvents(String subscribedEvents) {
        this.subscribedEvents = subscribedEvents != null && !subscribedEvents.isBlank() ? subscribedEvents.strip() : "*";
    }

    boolean isActive() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

