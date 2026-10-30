package com.inpartnerhub.diligence.qna.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.dataroom.DocumentDirectory;
import com.inpartnerhub.diligence.dataroom.DocumentDirectory.DocumentSummary;
import com.inpartnerhub.diligence.finding.FindingDirectory;
import com.inpartnerhub.diligence.finding.FindingDirectory.FindingSummary;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.MemberSummary;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.qna.QnaEvents.QuestionStatusChanged;
import com.inpartnerhub.diligence.request.RequestItemDirectory;
import com.inpartnerhub.diligence.request.RequestItemDirectory.ItemSummary;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

class QuestionServiceTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID LEGAL = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final AnswerDocumentRepository answerDocuments = mock(AnswerDocumentRepository.class);
    private final QuestionStatusChangeRepository history = mock(QuestionStatusChangeRepository.class);
    private final QnaAccess access = mock(QnaAccess.class);
    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final DocumentDirectory documents = mock(DocumentDirectory.class);
    private final RequestItemDirectory requestItems = mock(RequestItemDirectory.class);
    private final FindingDirectory findings = mock(FindingDirectory.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final QuestionService service = new QuestionService(questions, answers, answerDocuments, history,
            access, projects, documents, requestItems, findings, currentUser, events,
            Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC));

    private final List<QuestionStatusChange> changes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(currentUser.requireCurrentUser()).thenReturn(new AuthenticatedUser(ME, "me", "me@x.test", "Me",
                Set.of(Role.PROJECT_MANAGER)));
        when(questions.nextNumber(PROJECT)).thenReturn("Q-004");
        when(questions.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(history.save(any())).thenAnswer(inv -> {
            changes.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(projects.members(PROJECT)).thenReturn(List.of(new MemberSummary(TARGET, ProjectRole.TARGET, LEGAL)));
    }

    private ProjectContext as(ProjectRole role) {
        var context = new ProjectContext(PROJECT, "DD-1", false, Set.of(role), Set.of());
        when(access.project(PROJECT)).thenReturn(context);
        when(access.writableProject(PROJECT)).thenReturn(context);
        return context;
    }

    private Question existing(QuestionStatus status, UUID askedBy) {
        var q = new Question(PROJECT, "Q-001", askedBy, QuestionStatus.DRAFT);
        q.write("Please explain the land titles", new Question.Links(LEGAL, null, null, null), null, null);
        if (status != QuestionStatus.DRAFT) {
            q.moveTo(status, askedBy, Instant.now(), null);
        }
        when(access.question(any(), eq(q.getId()))).thenReturn(q);
        return q;
    }

    private static QuestionService.QuestionCommand command(Question.Links links) {
        return new QuestionService.QuestionCommand("Why did revenue drop in 2024?", links, null, null);
    }

    @Test
    void teamDraftsAndLinksQuestions() {
        as(ProjectRole.ANALYST);
        var item = UUID.randomUUID();
        var finding = UUID.randomUUID();
        when(requestItems.find(PROJECT, item)).thenReturn(Optional.of(new ItemSummary(item, "LEG-001", "Deed",
                LEGAL)));
        when(findings.find(PROJECT, finding)).thenReturn(Optional.of(new FindingSummary(finding, "F-LEG-001", "t",
                LEGAL, "DRAFT")));

        var q = service.create(PROJECT, command(new Question.Links(null, item, null, finding)), false);

        assertThat(q.getNumber()).isEqualTo("Q-004");
        assertThat(q.getStatus()).isEqualTo(QuestionStatus.DRAFT);
        assertThat(q.getWorkstreamId()).isEqualTo(LEGAL); // taken from the request item
        assertThat(changes).singleElement().extracting(QuestionStatusChange::getToStatus)
                .isEqualTo(QuestionStatus.DRAFT);
        verify(events, never()).publishEvent(any(QuestionStatusChanged.class));
    }

    @Test
    void clientQuestionsGoToApprovalAndCannotCiteFindings() {
        as(ProjectRole.CLIENT);
        var q = service.create(PROJECT, command(null), false);
        assertThat(q.getStatus()).isEqualTo(QuestionStatus.PENDING_APPROVAL);
        verify(events).publishEvent(any(QuestionStatusChanged.class));

        assertThatThrownBy(() -> service.create(PROJECT, command(new Question.Links(null, null, null,
                UUID.randomUUID())), false)).isInstanceOf(AccessDeniedException.class);
        as(ProjectRole.TARGET);
        assertThatThrownBy(() -> service.create(PROJECT, command(null), false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validatesBodyLinksAndRecipient() {
        as(ProjectRole.ANALYST);
        assertThatThrownBy(() -> service.create(PROJECT, new QuestionService.QuestionCommand(" ", null, null, null),
                false)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(PROJECT, command(new Question.Links(null, UUID.randomUUID(), null,
                null)), false)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(PROJECT, command(new Question.Links(null, null, UUID.randomUUID(),
                null)), false)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(PROJECT, new QuestionService.QuestionCommand("q",
                new Question.Links(LEGAL, null, null, null), ME, null), true))
                .hasMessageContaining("recipient");
        var doc = UUID.randomUUID();
        when(documents.find(PROJECT, doc)).thenReturn(Optional.of(new DocumentSummary(doc, UUID.randomUUID(), "a.pdf",
                "application/pdf", 1, "CLEAN")));
        var q = service.create(PROJECT, new QuestionService.QuestionCommand("q",
                new Question.Links(LEGAL, null, doc, null), TARGET, null), true);
        assertThat(q.getStatus()).isEqualTo(QuestionStatus.PENDING_APPROVAL);
        assertThat(q.getRecipientUserId()).isEqualTo(TARGET);
    }

    @Test
    void onlyManagersSendAndReturnQuestions() {
        as(ProjectRole.ANALYST);
        var q = existing(QuestionStatus.DRAFT, ME);
        service.changeStatus(PROJECT, q.getId(), QuestionStatus.PENDING_APPROVAL, null);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, q.getId(), QuestionStatus.SENT, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, q.getId(), QuestionStatus.DRAFT, "x"))
                .isInstanceOf(AccessDeniedException.class);

        as(ProjectRole.PROJECT_MANAGER);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, q.getId(), QuestionStatus.DRAFT, " "))
                .hasMessageContaining("reason");
        service.changeStatus(PROJECT, q.getId(), QuestionStatus.DRAFT, "Too broad");
        assertThat(q.getFeedback()).isEqualTo("Too broad");
        service.changeStatus(PROJECT, q.getId(), QuestionStatus.SENT, null);
        assertThat(q.getStatus()).isEqualTo(QuestionStatus.SENT);
        assertThat(q.getApprovedBy()).isEqualTo(ME);
        assertThat(q.getSentAt()).isEqualTo(Instant.parse("2026-11-20T03:00:00Z"));
        assertThat(q.getFeedback()).isNull();
    }

    @Test
    void targetAnswersAndTheTeamClosesOrFollowsUp() {
        as(ProjectRole.TARGET);
        var q = existing(QuestionStatus.SENT, UUID.randomUUID());
        assertThatThrownBy(() -> service.answer(PROJECT, q.getId(), "")).isInstanceOf(InvalidRequestException.class);
        service.answer(PROJECT, q.getId(), "Registered in 2025");
        assertThat(q.getStatus()).isEqualTo(QuestionStatus.ANSWERED);
        var answer = ArgumentCaptor.forClass(Answer.class);
        verify(answers).save(answer.capture());
        assertThat(answer.getValue().getBody()).isEqualTo("Registered in 2025");

        when(answers.findById(q.getId())).thenReturn(Optional.of(answer.getValue()));
        service.answer(PROJECT, q.getId(), "Registered in March 2025");
        assertThat(answer.getValue().getBody()).isEqualTo("Registered in March 2025");

        assertThatThrownBy(() -> service.changeStatus(PROJECT, q.getId(), QuestionStatus.CLOSED, null))
                .isInstanceOf(AccessDeniedException.class);

        as(ProjectRole.ANALYST);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, q.getId(), QuestionStatus.SENT, null))
                .hasMessageContaining("still missing");
        service.changeStatus(PROJECT, q.getId(), QuestionStatus.SENT, "Please attach the certificate");
        assertThat(q.getFeedback()).isEqualTo("Please attach the certificate");
        q.moveTo(QuestionStatus.ANSWERED, TARGET, Instant.now(), null);
        service.changeStatus(PROJECT, q.getId(), QuestionStatus.CLOSED, null);
        assertThat(q.getClosedAt()).isNotNull();
        assertThatThrownBy(() -> service.answer(PROJECT, q.getId(), "late"))
                .isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, q.getId(), QuestionStatus.ANSWERED, null))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void answerDocumentsFollowOwnership() {
        as(ProjectRole.TARGET);
        var q = existing(QuestionStatus.SENT, UUID.randomUUID());
        var doc = UUID.randomUUID();
        when(documents.find(PROJECT, doc)).thenReturn(Optional.of(new DocumentSummary(doc, UUID.randomUUID(),
                "cert.pdf", "application/pdf", 1, "CLEAN")));
        service.linkDocument(PROJECT, q.getId(), doc);
        verify(answerDocuments).save(any());

        var foreign = new AnswerDocument(q, doc, UUID.randomUUID());
        when(answerDocuments.findById(new AnswerDocument.Key(q.getId(), doc))).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.unlinkDocument(PROJECT, q.getId(), doc))
                .isInstanceOf(AccessDeniedException.class);
        var own = new AnswerDocument(q, doc, ME);
        when(answerDocuments.findById(new AnswerDocument.Key(q.getId(), doc))).thenReturn(Optional.of(own));
        service.unlinkDocument(PROJECT, q.getId(), doc);
        verify(answerDocuments).delete(own);
    }

    @Test
    void draftsAreEditedAndDeletedByTheTeamOnly() {
        as(ProjectRole.ANALYST);
        var q = existing(QuestionStatus.DRAFT, ME);
        service.update(PROJECT, q.getId(), command(new Question.Links(LEGAL, null, null, null)));
        assertThat(q.getBody()).startsWith("Why did revenue");
        service.delete(PROJECT, q.getId());
        verify(questions).delete(q);

        var sent = existing(QuestionStatus.SENT, ME);
        assertThatThrownBy(() -> service.update(PROJECT, sent.getId(), command(null)))
                .isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> service.delete(PROJECT, sent.getId())).isInstanceOf(ResourceConflictException.class);
        var theirs = existing(QuestionStatus.DRAFT, UUID.randomUUID());
        assertThatThrownBy(() -> service.delete(PROJECT, theirs.getId()))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void listDetailAndReferencesRespectTheBarrier() {
        var context = as(ProjectRole.TARGET);
        var q = existing(QuestionStatus.SENT, ME);
        var finding = UUID.randomUUID();
        q.write(q.getBody(), new Question.Links(LEGAL, null, null, finding), null, null);
        when(questions.findByProjectIdOrderByNumber(PROJECT)).thenReturn(List.of(q));
        when(answers.findByQuestionIdIn(anyCollection())).thenReturn(List.of());
        when(documents.findAll(eq(PROJECT), anyCollection())).thenReturn(List.of());

        assertThat(service.list(PROJECT, new QuestionService.QuestionFilter(QuestionStatus.SENT, LEGAL, "land")))
                .containsExactly(q);
        assertThat(service.list(PROJECT, new QuestionService.QuestionFilter(QuestionStatus.CLOSED, null, null)))
                .isEmpty();
        assertThat(service.answers(List.of(q))).isEmpty();
        var detail = service.detail(PROJECT, q.getId());
        assertThat(detail.references().findingNumber()).isNull();
        verify(findings, never()).find(any(), any());
        assertThat(context.isInternal()).isFalse();
    }
}
