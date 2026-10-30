package com.inpartnerhub.diligence.request.internal;

import static com.inpartnerhub.diligence.request.internal.RequestTestData.LEGAL;
import static com.inpartnerhub.diligence.request.internal.RequestTestData.PROJECT;
import static com.inpartnerhub.diligence.request.internal.RequestTestData.TAX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.inpartnerhub.diligence.notification.NotificationType;
import com.inpartnerhub.diligence.notification.Notifier;
import com.inpartnerhub.diligence.notification.Notifier.Notification;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.MemberSummary;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectSummary;
import com.inpartnerhub.diligence.project.ProjectRole;

class RequestReminderServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final UUID LEGAL_TARGET = UUID.randomUUID();
    private static final UUID ALL_TARGET = UUID.randomUUID();
    private static final UUID PM = UUID.randomUUID();

    private final RequestItemRepository items = mock(RequestItemRepository.class);
    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final Notifier notifier = mock(Notifier.class);
    private final RequestReminderService service = new RequestReminderService(items, projects, notifier, 3);

    @Test
    void remindsTargetsOncePerDayAndSendsManagersAnOverdueDigest() {
        var overdue = item(LEGAL, TODAY.minusDays(2));
        var dueSoon = item(TAX, TODAY.plusDays(3));
        when(items.dueForReminder(anyCollection(), eq(TODAY.plusDays(3)), eq(TODAY)))
                .thenReturn(List.of(overdue, dueSoon));
        when(projects.summary(PROJECT)).thenReturn(Optional.of(new ProjectSummary(PROJECT, "DD-1", "A", false)));
        when(projects.members(PROJECT)).thenReturn(List.of(
                new MemberSummary(LEGAL_TARGET, ProjectRole.TARGET, LEGAL),
                new MemberSummary(ALL_TARGET, ProjectRole.TARGET, null),
                new MemberSummary(PM, ProjectRole.PROJECT_MANAGER, null)));

        var sent = service.remind(TODAY);

        assertThat(sent).isEqualTo(3);
        var notifications = ArgumentCaptor.forClass(Notification.class);
        verify(notifier, times(3)).notify(notifications.capture());
        var byRecipients = notifications.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(Notification::recipients, n -> n));
        assertThat(byRecipients.get(Set.of(LEGAL_TARGET)).params())
                .containsEntry("dueSoon", "0").containsEntry("overdue", "1");
        assertThat(byRecipients.get(Set.of(ALL_TARGET)).params())
                .containsEntry("dueSoon", "1").containsEntry("overdue", "1");
        assertThat(byRecipients.get(Set.of(PM)).type()).isEqualTo(NotificationType.REQUEST_OVERDUE_DIGEST);
        assertThat(byRecipients.get(Set.of(PM)).params()).isEqualTo(Map.of("projectCode", "DD-1", "overdue", "1"));
        assertThat(overdue.getLastRemindedAt()).isEqualTo(TODAY);
        assertThat(dueSoon.getLastRemindedAt()).isEqualTo(TODAY);
    }

    @Test
    void archivedProjectsAreSkipped() {
        when(items.dueForReminder(anyCollection(), any(), any())).thenReturn(List.of(item(LEGAL, TODAY)));
        when(projects.summary(PROJECT)).thenReturn(Optional.of(new ProjectSummary(PROJECT, "DD-1", "A", true)));
        assertThat(service.remind(TODAY)).isZero();
        verify(notifier, never()).notify(any());
    }

    private static RequestItem item(UUID workstream, LocalDate due) {
        var item = new RequestItem(PROJECT, workstream, UUID.randomUUID().toString().substring(0, 8), "c", "d",
                null, due, 1);
        item.moveTo(RequestStatus.REQUESTED, Instant.now());
        return item;
    }
}
