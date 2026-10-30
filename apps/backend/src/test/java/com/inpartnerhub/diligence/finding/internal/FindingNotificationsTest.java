package com.inpartnerhub.diligence.finding.internal;

import static com.inpartnerhub.diligence.finding.internal.FindingTestData.LEGAL;
import static com.inpartnerhub.diligence.finding.internal.FindingTestData.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.inpartnerhub.diligence.finding.FindingEvents.FindingEscalated;
import com.inpartnerhub.diligence.finding.FindingEvents.FindingRef;
import com.inpartnerhub.diligence.finding.FindingEvents.FindingStatusChanged;
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.notification.NotificationType;
import com.inpartnerhub.diligence.notification.Notifier;
import com.inpartnerhub.diligence.notification.Notifier.Notification;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.MemberSummary;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

class FindingNotificationsTest {

    private static final UUID PARTNER = UUID.randomUUID();
    private static final UUID PM = UUID.randomUUID();
    private static final UUID ANALYST = UUID.randomUUID();
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final Notifier notifier = mock(Notifier.class);
    private final FindingNotifications listener = new FindingNotifications(projects, notifier);
    private final FindingRef ref = new FindingRef(UUID.randomUUID(), "F-LEG-001", "Title", LEGAL, ANALYST);

    @BeforeEach
    void setUp() {
        when(projects.members(PROJECT)).thenReturn(List.of(new MemberSummary(PARTNER, ProjectRole.PARTNER, null),
                new MemberSummary(PM, ProjectRole.PROJECT_MANAGER, null),
                new MemberSummary(ANALYST, ProjectRole.ANALYST, LEGAL),
                new MemberSummary(CLIENT, ProjectRole.CLIENT, null),
                new MemberSummary(TARGET, ProjectRole.TARGET, null)));
    }

    private Notification sent(String to, String note, UUID actor) {
        listener.on(new FindingStatusChanged(PROJECT, "DD-1", ref, "X", to, note,
                new RequestMetadata(actor, null, null)));
        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifier, org.mockito.Mockito.atLeastOnce()).notify(captor.capture());
        return captor.getValue();
    }

    @Test
    void submissionGoesToReviewers() {
        var n = sent("IN_REVIEW", null, ANALYST);
        assertThat(n.type()).isEqualTo(NotificationType.FINDING_SUBMITTED);
        assertThat(n.recipients()).containsExactlyInAnyOrder(PARTNER, PM);
        assertThat(n.link()).isEqualTo("/projects/" + PROJECT + "/findings?finding=" + ref.findingId());
    }

    @Test
    void returnGoesToTheAuthorWithTheReason() {
        var n = sent("DRAFT", "Cite the page", PM);
        assertThat(n.recipients()).containsExactly(ANALYST);
        assertThat(n.params()).containsEntry("reason", "Cite the page");
    }

    @Test
    void approvalGoesToAuthorAndPartnersAndReleaseOnlyToTheClient() {
        assertThat(sent("APPROVED", null, PM).recipients()).containsExactlyInAnyOrder(ANALYST, PARTNER);
        var released = sent("RELEASED", null, PARTNER);
        assertThat(released.type()).isEqualTo(NotificationType.FINDING_RELEASED);
        assertThat(released.recipients()).containsExactly(CLIENT);
    }

    @Test
    void archivingNotifiesNobody() {
        listener.on(new FindingStatusChanged(PROJECT, "DD-1", ref, "DRAFT", "ARCHIVED", "dup",
                RequestMetadata.SYSTEM));
        verify(notifier, never()).notify(any());
    }

    @Test
    void escalationFollowsAppendixA() {
        listener.on(new FindingEscalated(PROJECT, "DD-1", ref, RiskLevel.HIGH, 16, RequestMetadata.SYSTEM));
        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifier).notify(captor.capture());
        assertThat(captor.getValue().recipients()).containsExactly(PM);

        listener.on(new FindingEscalated(PROJECT, "DD-1", ref, RiskLevel.CRITICAL, 20,
                new RequestMetadata(PM, null, null)));
        verify(notifier, org.mockito.Mockito.times(2)).notify(captor.capture());
        assertThat(captor.getValue().recipients()).containsExactly(PARTNER);
        assertThat(captor.getValue().params()).containsEntry("level", "CRITICAL").containsEntry("score", "20");
    }
}
