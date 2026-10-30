package com.inpartnerhub.diligence.notification.internal;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.notification.EmailSender;
import com.inpartnerhub.diligence.notification.NotificationType;
import com.inpartnerhub.diligence.notification.Notifier;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivery and inbox (PRD ADM-05): every notification is stored in the recipient's inbox and e-mailed, unless the
 * recipient switched that channel off for the type. E-mail failures are logged and do not undo the in-app delivery.
 */
@Service
@Transactional
class NotificationService implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {
    };

    record Preference(NotificationType type, boolean emailEnabled, boolean inAppEnabled) {
    }

    record InboxEntry(UUID id, NotificationType type, UUID projectId, Map<String, String> params, String link,
            Instant createdAt, Instant readAt) {
    }

    private final InAppNotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final Directory directory;
    private final EmailSender email;
    private final CurrentUserProvider currentUser;
    private final JsonMapper json;
    private final String appBaseUrl;

    NotificationService(InAppNotificationRepository notifications, NotificationPreferenceRepository preferences,
            Directory directory, EmailSender email, CurrentUserProvider currentUser, JsonMapper json,
            @Value("${diligence.notification.app-base-url}") String appBaseUrl) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.directory = directory;
        this.email = email;
        this.currentUser = currentUser;
        this.json = json;
        this.appBaseUrl = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
    }

    @Override
    public void notify(Notification notification) {
        if (notification.recipients().isEmpty()) {
            return;
        }
        var recipients = directory.findUsers(List.copyOf(notification.recipients())).stream()
                .filter(Directory.UserSummary::active).toList();
        var chosen = preferences.findByNotificationTypeAndUserIdIn(notification.type().name(),
                        notification.recipients()).stream()
                .collect(Collectors.toMap(NotificationPreference::getUserId, Function.identity()));
        var params = json.writeValueAsString(notification.params());
        var url = appBaseUrl + (notification.link() == null ? "/" : notification.link());
        for (var user : recipients) {
            var preference = chosen.get(user.id());
            if (preference == null || preference.isInAppEnabled()) {
                notifications.save(new InAppNotification(user.id(), notification.type(), notification.projectId(),
                        params, notification.link()));
            }
            if ((preference == null || preference.isEmailEnabled()) && user.email() != null) {
                sendQuietly(NotificationMessages.email(user.email(), notification.type(), notification.params(), url));
            }
        }
    }

    @Transactional(readOnly = true)
    Page<InboxEntry> inbox(boolean unreadOnly, Pageable pageable) {
        var userId = me();
        var page = unreadOnly ? notifications.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, pageable)
                : notifications.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return page.map(this::entry);
    }

    @Transactional(readOnly = true)
    long unreadCount() {
        return notifications.countByUserIdAndReadAtIsNull(me());
    }

    void markRead(UUID id) {
        var userId = me();
        notifications.findById(id).filter(n -> n.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Notification", id))
                .markRead(Instant.now());
    }

    int markAllRead() {
        return notifications.markAllRead(me(), Instant.now());
    }

    /** All types with the caller's choices; types without a stored choice have both channels on. */
    @Transactional(readOnly = true)
    List<Preference> preferences() {
        var stored = preferences.findByUserId(me()).stream()
                .collect(Collectors.toMap(NotificationPreference::getNotificationType, Function.identity()));
        return Arrays.stream(NotificationType.values()).map(type -> {
            var p = stored.get(type.name());
            return p == null ? new Preference(type, true, true)
                    : new Preference(type, p.isEmailEnabled(), p.isInAppEnabled());
        }).toList();
    }

    List<Preference> updatePreferences(List<Preference> changes) {
        var userId = me();
        for (var change : changes) {
            preferences.findById(new NotificationPreference.Key(userId, change.type().name()))
                    .ifPresentOrElse(p -> p.change(change.emailEnabled(), change.inAppEnabled()),
                            () -> preferences.save(new NotificationPreference(userId, change.type().name(),
                                    change.emailEnabled(), change.inAppEnabled())));
        }
        preferences.flush();
        return preferences();
    }

    private InboxEntry entry(InAppNotification n) {
        return new InboxEntry(n.getId(), n.getType(), n.getProjectId(), json.readValue(n.getParams(), PARAMS),
                n.getLink(), n.getCreatedAt(), n.getReadAt());
    }

    private void sendQuietly(EmailSender.EmailMessage message) {
        try {
            email.send(message);
        } catch (RuntimeException ex) {
            log.warn("Notification e-mail could not be sent: {}", ex.getMessage());
        }
    }

    private UUID me() {
        return currentUser.requireCurrentUser().id();
    }
}
