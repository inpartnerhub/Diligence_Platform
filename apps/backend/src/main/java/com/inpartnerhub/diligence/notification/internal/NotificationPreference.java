package com.inpartnerhub.diligence.notification.internal;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Per-user, per-notification-type channel preferences (PRD ADM-05). Without a row both channels are on. */
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

    NotificationPreference(UUID userId, String notificationType, boolean emailEnabled, boolean inAppEnabled) {
        this.userId = userId;
        this.notificationType = notificationType;
        this.emailEnabled = emailEnabled;
        this.inAppEnabled = inAppEnabled;
    }

    void change(boolean emailEnabled, boolean inAppEnabled) {
        this.emailEnabled = emailEnabled;
        this.inAppEnabled = inAppEnabled;
    }

    UUID getUserId() {
        return userId;
    }

    String getNotificationType() {
        return notificationType;
    }

    boolean isEmailEnabled() {
        return emailEnabled;
    }

    boolean isInAppEnabled() {
        return inAppEnabled;
    }
}
