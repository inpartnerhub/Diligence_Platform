package com.inpartnerhub.diligence.notification.internal;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Per-user, per-notification-type channel preferences (PRD ADM-05). */
@Entity
@IdClass(NotificationPreference.Key.class)
@Table(schema = "ntf", name = "notification_preference")
class NotificationPreference {

    record Key(UUID userId, String notificationType) {

        Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "notification_type")
    private String notificationType;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled = true;

    protected NotificationPreference() {
    }
}
