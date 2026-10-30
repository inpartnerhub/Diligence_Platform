package com.inpartnerhub.diligence.request.internal;

import static com.inpartnerhub.diligence.request.internal.RequestTestData.LEGAL;
import static com.inpartnerhub.diligence.request.internal.RequestTestData.PROJECT;
import static com.inpartnerhub.diligence.request.internal.RequestTestData.TAX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.inpartnerhub.diligence.notification.NotificationType;
import com.inpartnerhub.diligence.notification.Notifier;
import com.inpartnerhub.diligence.notification.Notifier.Notification;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.MemberSummary;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.request.RequestEvents.ItemRef;
import com.inpartnerhub.diligence.request.RequestEvents.RequestCommentAdded;
import com.inpartnerhub.diligence.request.RequestEvents.RequestItemsReleased;
import com.inpartnerhub.diligence.request.RequestEvents.RequestStatusChanged;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

class RequestNotificationsTest {

    private static final UUID PM = UUID.randomUUID();
    private static final UUID TAX_ANALYST = UUID.randomUUID();
    private static final UUID LEGAL_ANALYST = UUID.randomUUID();
    private static final UUID LEGAL_TARGET = UUID.randomUUID();
    private static final UUID TAX_TARGET = UUID.randomUUID();
    private static final UUID CLIENT = UUID.randomUUID();

    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final Notifier notifier = mock(Notifier.class);
    private final RequestNotifications listener = new RequestNotifications(projects, notifier);

    @BeforeEach
    void setUp() {
        when(projects.members(PROJECT)).thenReturn(List.of(
                new MemberSummary(PM, ProjectRole.PROJECT_MANAGER, null),
                new MemberSummary(TAX_ANALYST, ProjectRole.ANALYST, TAX),
                new MemberSummary(LEGAL_ANALYST, ProjectRole.ANALYST, LEGAL),
                new MemberSummary(LEGAL_TARGET, ProjectRole.TARGET, LEGAL),
                new MemberSummary(TAX_TARGET, ProjectRole.TARGET, TAX),
                new MemberSummary(CLIENT, ProjectRole.CLIENT, null)));
    }

    @Test
    void releasesNotifyTheTargetsOfTheItemsWorkstreams() {
        listener.on(new RequestItemsReleased(PROJECT, "DD-1", 2, List.of(ref(LEGAL, null), ref(LEGAL, null),
                ref(TAX, null)), RequestMetadata.SYSTEM));

        var sent = ArgumentCaptor.forClass(Notification.class);
        verify(notifier, times(2)).notify(sent.capture());
        assertThat(sent.getAllValues()).anySatisfy(n -> {
            assertThat(n.recipients()).containsExactly(LEGAL_TARGET);
            assertThat(n.params()).containsEntry("count", "2").containsEntry("wave", "2");
            assertThat(n.link()).isEqualTo("/projects/" + PROJECT + "/requests");
        });
        assertThat(sent.getAllValues()).anySatisfy(n -> assertThat(n.recipients()).containsExactly(TAX_TARGET));
    }

    @Test
    void deliveriesGoToManagersAndTheWorkstreamsAnalysts() {
        var item = ref(LEGAL, null);
        listener.on(new RequestStatusChanged(PROJECT, "DD-1", item, "REQUESTED", "RECEIVED", true, null,
                new RequestMetadata(LEGAL_TARGET, null, null)));

        var sent = ArgumentCaptor.forClass(Notification.class);
        verify(notifier).notify(sent.capture());
        assertThat(sent.getValue().type()).isEqualTo(NotificationType.REQUEST_SUBMITTED);
        assertThat(sent.getValue().recipients()).containsExactlyInAnyOrder(PM, LEGAL_ANALYST);
        assertThat(sent.getValue().link()).endsWith("?item=" + item.itemId());
    }

    @Test
    void reviewsGoToTheAssigneeOnlyAndOtherChangesToNobody() {
        listener.on(new RequestStatusChanged(PROJECT, "DD-1", ref(TAX, TAX_TARGET), "RECEIVED", "REJECTED", false,
                "Unsigned", new RequestMetadata(PM, null, null)));
        var sent = ArgumentCaptor.forClass(Notification.class);
        verify(notifier).notify(sent.capture());
        assertThat(sent.getValue().recipients()).containsExactly(TAX_TARGET);
        assertThat(sent.getValue().params()).containsEntry("reason", "Unsigned").containsEntry("status", "REJECTED");

        listener.on(new RequestStatusChanged(PROJECT, "DD-1", ref(TAX, null), "PARTIAL", "RECEIVED", false, null,
                RequestMetadata.SYSTEM));
        verify(notifier, times(1)).notify(any());
    }

    @Test
    void internalNotesAreNeverAnnouncedAndAuthorsAreNotNotified() {
        listener.on(new RequestCommentAdded(PROJECT, "DD-1", ref(LEGAL, null), UUID.randomUUID(), PM, false, true,
                RequestMetadata.SYSTEM));
        verify(notifier, never()).notify(any());

        listener.on(new RequestCommentAdded(PROJECT, "DD-1", ref(LEGAL, null), UUID.randomUUID(), LEGAL_ANALYST,
                false, false, RequestMetadata.SYSTEM));
        var sent = ArgumentCaptor.forClass(Notification.class);
        verify(notifier).notify(sent.capture());
        assertThat(sent.getValue().recipients()).isEqualTo(Set.of(LEGAL_TARGET));

        listener.on(new RequestCommentAdded(PROJECT, "DD-1", ref(LEGAL, LEGAL_TARGET), UUID.randomUUID(),
                LEGAL_TARGET, true, false, RequestMetadata.SYSTEM));
        verify(notifier, times(2)).notify(sent.capture());
        assertThat(sent.getValue().recipients()).containsExactlyInAnyOrder(PM, LEGAL_ANALYST);
    }

    @Test
    void longDescriptionsAreShortened() {
        var params = RequestNotifications.itemParams("DD-1", new ItemRef(UUID.randomUUID(), "LEG-001",
                "x".repeat(300), LEGAL, null));
        assertThat(params.get("description")).hasSize(120).endsWith("...");
    }

    private static ItemRef ref(UUID workstream, UUID assignee) {
        return new ItemRef(UUID.randomUUID(), "LEG-001", "Deed", workstream, assignee);
    }
}
