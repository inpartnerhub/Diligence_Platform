package com.inpartnerhub.diligence.notification.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, NotificationPreference.Key> {

    List<NotificationPreference> findByUserId(UUID userId);

    List<NotificationPreference> findByNotificationTypeAndUserIdIn(String notificationType, Collection<UUID> userIds);
}
