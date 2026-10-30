package com.inpartnerhub.diligence.qna.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.notification.NotificationType;
import com.inpartnerhub.diligence.notification.Notifier;
import com.inpartnerhub.diligence.notification.Notifier.Notification;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.MemberSummary;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.qna.QnaEvents.QnaExported;
import com.inpartnerhub.diligence.qna.QnaEvents.QuestionRef;
import com.inpartnerhub.diligence.qna.QnaEvents.QuestionStatusChanged;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

class QnaNotificationsAndExportTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID LEGAL = UUID.randomUUID();
    private static final UUID PM = UUID.randomUUID();
    private static final UUID ANALYST = UUID.randomUUID();
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID OTHER_TARGET = UUID.randomUUID();

    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final Notifier notifier = mock(Notifier.class);
    private final QnaNotifications listener = new QnaNotifications(projects, notifier);

    private Notification on(String to, UUID askedBy, UUID recipient, String feedback) {
        when(projects.members(PROJECT)).thenReturn(List.of(new MemberSummary(PM, ProjectRole.PROJECT_MANAGER, null),
                new MemberSummary(ANALYST, ProjectRole.ANALYST, LEGAL),
                new MemberSummary(CLIENT, ProjectRole.CLIENT, null),
                new MemberSummary(TARGET, ProjectRole.TARGET, LEGAL),
                new MemberSummary(OTHER_TARGET, ProjectRole.TARGET, UUID.randomUUID())));
        var ref = new QuestionRef(UUID.randomUUID(), "Q-001", "Please explain\nthe land titles", LEGAL, askedBy,
                recipient);
        listener.on(new QuestionStatusChanged(PROJECT, "DD-1", ref, "X", to, feedback, RequestMetadata.SYSTEM));
        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifier, org.mockito.Mockito.atLeastOnce()).notify(captor.capture());
        return captor.getValue();
    }

    @Test
    void approvalAndSending() {
        var pending = on("PENDING_APPROVAL", CLIENT, null, null);
        assertThat(pending.type()).isEqualTo(NotificationType.QUESTION_PENDING_APPROVAL);
        assertThat(pending.recipients()).containsExactly(PM);
        assertThat(pending.params()).containsEntry("excerpt", "Please explain the land titles");
    }

    @Test
    void sentGoesToTheWorkstreamsTargetsOrTheRecipient() {
        assertThat(on("SENT", ANALYST, null, null).recipients()).containsExactly(TARGET);
    }

    @Test
    void namedRecipientOnly() {
        assertThat(on("SENT", ANALYST, OTHER_TARGET, "more please").params()).containsEntry("feedback", "more please");
    }

    @Test
    void answersReachTheTeamButNotAClientAsker() {
        var answered = on("ANSWERED", CLIENT, null, null);
        assertThat(answered.recipients()).containsExactlyInAnyOrder(PM, ANALYST);
    }

    @Test
    void closingNotifiesOnlyAClientAsker() {
        var closed = on("CLOSED", CLIENT, null, null);
        assertThat(closed.type()).isEqualTo(NotificationType.QUESTION_CLOSED);
        assertThat(closed.recipients()).containsExactly(CLIENT);
    }

    @Test
    void closingAnInternalQuestionIsSilent() {
        when(projects.members(PROJECT)).thenReturn(List.of(new MemberSummary(ANALYST, ProjectRole.ANALYST, LEGAL)));
        listener.on(new QuestionStatusChanged(PROJECT, "DD-1", new QuestionRef(UUID.randomUUID(), "Q-1", "b", LEGAL,
                ANALYST, null), "ANSWERED", "CLOSED", null, RequestMetadata.SYSTEM));
        verify(notifier, never()).notify(any());
    }

    @Test
    void returnGoesToTheAsker() {
        var returned = on("DRAFT", ANALYST, null, "Too broad");
        assertThat(returned.type()).isEqualTo(NotificationType.QUESTION_RETURNED);
        assertThat(returned.recipients()).containsExactly(ANALYST);
    }

    @Test
    void exportsTheVisibleLogAsExcelAndPdf() throws IOException {
        var questions = mock(QuestionService.class);
        var access = mock(QnaAccess.class);
        var renderer = mock(PreviewRenderer.class);
        var events = mock(ApplicationEventPublisher.class);
        var service = new QnaExportService(questions, access, renderer, events, ZoneId.of("Asia/Jakarta"));
        when(access.project(PROJECT)).thenReturn(new ProjectContext(PROJECT, "DD-1", false,
                Set.of(ProjectRole.PROJECT_MANAGER), Set.of()));
        var q = new Question(PROJECT, "Q-001", PM, QuestionStatus.DRAFT);
        q.write("Land titles?", new Question.Links(LEGAL, null, null, null), null, null);
        q.moveTo(QuestionStatus.SENT, PM, Instant.parse("2026-11-20T03:00:00Z"), null);
        when(questions.list(PROJECT, QuestionService.QuestionFilter.ALL)).thenReturn(List.of(q));
        when(questions.answers(List.of(q))).thenReturn(Map.of(q.getId(), new Answer(q, "Registered", TARGET)));

        var file = service.export(PROJECT, QuestionService.QuestionFilter.ALL, QnaExportService.Format.XLSX);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            var row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("Q-001");
            assertThat(row.getCell(3).getStringCellValue()).isEqualTo("2026-11-20 10:00");
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("Registered");
        }
        verify(events).publishEvent(any(QnaExported.class));

        doAnswer(inv -> Files.write((Path) inv.getArgument(3), "%PDF".getBytes()))
                .when(renderer).renderPdf(any(), anyString(), eq(QnaExportService.XLSX), any());
        assertThat(service.export(PROJECT, QuestionService.QuestionFilter.ALL, QnaExportService.Format.PDF)
                .contentType()).isEqualTo("application/pdf");
        doThrow(new IllegalStateException("down")).when(renderer).renderPdf(any(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.export(PROJECT, QuestionService.QuestionFilter.ALL,
                QnaExportService.Format.PDF)).isInstanceOf(UpstreamServiceException.class);
    }
}
