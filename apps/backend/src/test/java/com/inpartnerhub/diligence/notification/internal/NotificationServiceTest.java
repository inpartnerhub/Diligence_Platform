package com.inpartnerhub.diligence.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.notification.EmailSender;
import com.inpartnerhub.diligence.notification.EmailSender.EmailMessage;
import com.inpartnerhub.diligence.notification.NotificationType;
import com.inpartnerhub.diligence.notification.Notifier.Notification;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

import tools.jackson.databind.json.JsonMapper;

class NotificationServiceTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID INACTIVE = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();

    private final InAppNotificationRepository notifications = mock(InAppNotificationRepository.class);
    private final NotificationPreferenceRepository preferences = mock(NotificationPreferenceRepository.class);
    private final Directory directory = mock(Directory.class);
    private final EmailSender email = mock(EmailSender.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final NotificationService service = new NotificationService(notifications, preferences, directory, email,
            currentUser, JsonMapper.builder().build(), "https://dd.example/");

    private final List<InAppNotification> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(currentUser.requireCurrentUser()).thenReturn(new AuthenticatedUser(ME, "me", "me@x.test", "Me",
                Set.of(Role.TARGET)));
        when(notifications.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(directory.findUsers(any())).thenReturn(List.of(
                new Directory.UserSummary(ME, "me@x.test", "Me", null, Role.TARGET, true),
                new Directory.UserSummary(OTHER, "other@x.test", "Other", null, Role.TARGET, true),
                new Directory.UserSummary(INACTIVE, "gone@x.test", "Gone", null, Role.TARGET, false)));
    }

    @Test
    void deliversInAppAndByEmailAccordingToPreferences() {
        when(preferences.findByNotificationTypeAndUserIdIn(eq("REQUEST_RELEASED"), anyCollection())).thenReturn(
                List.of(new NotificationPreference(OTHER, "REQUEST_RELEASED", false, true)));

        service.notify(new Notification(Set.of(ME, OTHER, INACTIVE), NotificationType.REQUEST_RELEASED, PROJECT,
                Map.of("projectCode", "DD-1", "count", "3", "wave", "1"), "/projects/" + PROJECT + "/requests"));

        assertThat(saved).extracting(InAppNotification::getUserId).containsExactlyInAnyOrder(ME, OTHER);
        assertThat(saved.getFirst().getParams()).contains("\"count\":\"3\"");
        var mail = ArgumentCaptor.forClass(EmailMessage.class);
        verify(email).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("me@x.test");
        assertThat(mail.getValue().subject()).isEqualTo("[DD-1] 3 permintaan dokumen baru / new document requests");
        assertThat(mail.getValue().plainTextBody()).contains("gelombang 1", "wave 1",
                "https://dd.example/projects/" + PROJECT + "/requests");
    }

    @Test
    void emailFailuresDoNotStopDelivery() {
        doThrow(new IllegalStateException("smtp down")).when(email).send(any());
        service.notify(new Notification(Set.of(ME, OTHER), NotificationType.REQUEST_DUE_REMINDER, PROJECT,
                Map.of("projectCode", "DD-1", "dueSoon", "1", "overdue", "2"), null));
        assertThat(saved).hasSize(2);
        verify(email, times(2)).send(any());
    }

    @Test
    void nobodyToNotifyDoesNothing() {
        service.notify(new Notification(Set.of(), NotificationType.REQUEST_COMMENTED, PROJECT, Map.of(), null));
        verify(directory, never()).findUsers(any());
    }

    @Test
    void everyTypeHasAnEmailText() {
        for (var type : NotificationType.values()) {
            var message = NotificationMessages.email("a@x.test", type, Map.of("projectCode", "DD-1",
                    "status", type == NotificationType.REQUEST_REVIEWED ? "REJECTED" : "CLOSED"), "https://x/");
            assertThat(message.subject()).startsWith("[DD-1]");
            assertThat(message.plainTextBody()).contains("https://x/", "Diligence Platform");
        }
        assertThat(NotificationMessages.email("a@x.test", NotificationType.REQUEST_REVIEWED,
                Map.of("projectCode", "DD-1", "itemNumber", "LEG-1", "status", "CLOSED"), "u").subject())
                .contains("ditutup", "closed");
    }

    @Test
    void inboxReadsAndMarksOnlyTheCallersNotifications() {
        var mine = new InAppNotification(ME, NotificationType.REQUEST_COMMENTED, PROJECT, "{\"itemNumber\":\"LEG-1\"}",
                "/x");
        var theirs = new InAppNotification(OTHER, NotificationType.REQUEST_COMMENTED, PROJECT, "{}", null);
        var page = PageRequest.of(0, 20);
        when(notifications.findByUserIdOrderByCreatedAtDesc(ME, page)).thenReturn(new PageImpl<>(List.of(mine)));
        when(notifications.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(ME, page))
                .thenReturn(new PageImpl<>(List.of()));
        when(notifications.countByUserIdAndReadAtIsNull(ME)).thenReturn(4L);
        when(notifications.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(notifications.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        when(notifications.markAllRead(eq(ME), any())).thenReturn(3);

        assertThat(service.inbox(false, page).getContent()).singleElement()
                .satisfies(e -> assertThat(e.params()).containsEntry("itemNumber", "LEG-1"));
        assertThat(service.inbox(true, page)).isEmpty();
        assertThat(service.unreadCount()).isEqualTo(4);
        service.markRead(mine.getId());
        assertThat(mine.getReadAt()).isNotNull();
        var firstRead = mine.getReadAt();
        service.markRead(mine.getId());
        assertThat(mine.getReadAt()).isEqualTo(firstRead);
        assertThatThrownBy(() -> service.markRead(theirs.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.markAllRead()).isEqualTo(3);
    }

    @Test
    void preferencesDefaultToBothChannelsAndCanBeChanged() {
        var stored = new NotificationPreference(ME, "REQUEST_COMMENTED", false, true);
        when(preferences.findByUserId(ME)).thenReturn(List.of(stored));
        when(preferences.findById(new NotificationPreference.Key(ME, "REQUEST_COMMENTED")))
                .thenReturn(Optional.of(stored));

        var all = service.preferences();
        assertThat(all).hasSize(NotificationType.values().length);
        assertThat(all).filteredOn(p -> p.type() == NotificationType.REQUEST_COMMENTED).singleElement()
                .satisfies(p -> assertThat(p.emailEnabled()).isFalse());
        assertThat(all).filteredOn(p -> p.type() != NotificationType.REQUEST_COMMENTED)
                .allMatch(p -> p.emailEnabled() && p.inAppEnabled());

        service.updatePreferences(List.of(
                new NotificationService.Preference(NotificationType.REQUEST_COMMENTED, true, false),
                new NotificationService.Preference(NotificationType.REQUEST_RELEASED, false, false)));
        assertThat(stored.isEmailEnabled()).isTrue();
        assertThat(stored.isInAppEnabled()).isFalse();
        var created = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(preferences).save(created.capture());
        assertThat(created.getValue().getNotificationType()).isEqualTo("REQUEST_RELEASED");
    }
}
