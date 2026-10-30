package com.inpartnerhub.diligence.notification.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnTransformer;

import com.inpartnerhub.diligence.notification.NotificationType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An in-app notification shown in the recipient's inbox (PRD ADM-05). */
@Entity
@Table(schema = "ntf", name = "notification")
class InAppNotification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, updatable = false)
    private NotificationType type;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    /** Message parameters as a JSON object of strings. */
    @Column(columnDefinition = "jsonb", nullable = false, updatable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String params;

    @Column(updatable = false)
    private String link;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected InAppNotification() {
    }

    InAppNotification(UUID userId, NotificationType type, UUID projectId, String params, String link) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.type = type;
        this.projectId = projectId;
        this.params = params;
        this.link = link;
        this.createdAt = Instant.now();
    }

    void markRead(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
    }

    UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    NotificationType getType() {
        return type;
    }

    UUID getProjectId() {
        return projectId;
    }

    String getParams() {
        return params;
    }

    String getLink() {
        return link;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getReadAt() {
        return readAt;
    }
}
