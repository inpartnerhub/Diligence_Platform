package com.inpartnerhub.diligence.audit.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(schema = "audit", name = "audit_log")
class AuditLogEntry {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String action;

    @Column(name = "object_type", nullable = false)
    private String objectType;

    @Column(name = "object_id")
    private String objectId;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(nullable = false)
    private String outcome;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String details;

    protected AuditLogEntry() {
    }

    AuditLogEntry(Instant occurredAt, UUID userId, String action, String objectType, String objectId,
            String ipAddress, String userAgent, String outcome, String details) {
        this.id = UUID.randomUUID();
        this.occurredAt = occurredAt;
        this.userId = userId;
        this.action = action;
        this.objectType = objectType;
        this.objectId = objectId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.outcome = outcome;
        this.details = details;
    }

    UUID getId() {
        return id;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    UUID getUserId() {
        return userId;
    }

    String getAction() {
        return action;
    }

    String getObjectType() {
        return objectType;
    }

    String getObjectId() {
        return objectId;
    }

    String getIpAddress() {
        return ipAddress;
    }

    String getUserAgent() {
        return userAgent;
    }

    String getOutcome() {
        return outcome;
    }

    String getDetails() {
        return details;
    }
}
