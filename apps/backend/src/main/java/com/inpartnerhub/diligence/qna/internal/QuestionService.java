package com.inpartnerhub.diligence.qna.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.dataroom.DocumentDirectory;
import com.inpartnerhub.diligence.dataroom.DocumentDirectory.DocumentSummary;
import com.inpartnerhub.diligence.finding.FindingDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.qna.QnaEvents.QuestionChanged;
import com.inpartnerhub.diligence.qna.QnaEvents.QuestionStatusChanged;
import com.inpartnerhub.diligence.request.RequestItemDirectory;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/**
 * Q&A workflow (PRD QNA-01, QNA-02). Nothing reaches the target before a partner or project manager approves it;
 * client questions start at approval. The target answers (with data-room documents), the team closes the question or
 * sends it back with a follow-up. Clients read answers only once their question is closed (row-level security).
 */
@Service
@Transactional
class QuestionService {

    record QuestionCommand(String body, Question.Links links, UUID recipientUserId, LocalDate dueDate) {
    }

    record QuestionFilter(QuestionStatus status, UUID workstreamId, String query) {

        static final QuestionFilter ALL = new QuestionFilter(null, null, null);

        boolean matches(Question q) {
            return (status == null || q.getStatus() == status)
                    && (workstreamId == null || workstreamId.equals(q.getWorkstreamId()))
                    && (query == null || query.isBlank()
                            || q.getNumber().toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT))
                            || q.getBody().toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT)));
        }
    }

    record LinkedDocument(DocumentSummary document, UUID linkedBy, Instant linkedAt) {
    }

    /** What a question refers to, as far as the caller may see (the target never sees findings). */
    record References(String requestItemNumber, String documentName, String findingNumber) {
    }

    record QuestionDetail(Question question, Optional<Answer> answer, List<LinkedDocument> documents,
            References references, List<QuestionStatusChange> history) {
    }

    private final QuestionRepository questions;
    private final AnswerRepository answers;
    private final AnswerDocumentRepository answerDocuments;
    private final QuestionStatusChangeRepository history;
    private final QnaAccess access;
    private final ProjectDirectory projects;
    private final DocumentDirectory documents;
    private final RequestItemDirectory requestItems;
    private final FindingDirectory findings;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    QuestionService(QuestionRepository questions, AnswerRepository answers, AnswerDocumentRepository answerDocuments,
            QuestionStatusChangeRepository history, QnaAccess access, ProjectDirectory projects,
            DocumentDirectory documents, RequestItemDirectory requestItems, FindingDirectory findings,
            CurrentUserProvider currentUser, ApplicationEventPublisher events) {
        this(questions, answers, answerDocuments, history, access, projects, documents, requestItems, findings,
                currentUser, events, Clock.systemDefaultZone());
    }

    QuestionService(QuestionRepository questions, AnswerRepository answers, AnswerDocumentRepository answerDocuments,
            QuestionStatusChangeRepository history, QnaAccess access, ProjectDirectory projects,
            DocumentDirectory documents, RequestItemDirectory requestItems, FindingDirectory findings,
            CurrentUserProvider currentUser, ApplicationEventPublisher events, Clock clock) {
        this.questions = questions;
        this.answers = answers;
        this.answerDocuments = answerDocuments;
        this.history = history;
        this.access = access;
        this.projects = projects;
        this.documents = documents;
        this.requestItems = requestItems;
        this.findings = findings;
        this.currentUser = currentUser;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    /** Questions visible to the caller (row-level security plus workstream scope), by number. */
    @Transactional(readOnly = true)
    List<Question> list(UUID projectId, QuestionFilter filter) {
        var context = access.project(projectId);
        return questions.findByProjectIdOrderByNumber(projectId).stream()
                .filter(q -> QnaAccess.isClient(context) || context.coversWorkstream(q.getWorkstreamId()))
                .filter(filter::matches)
                .toList();
    }

    /** Answers the caller may read (the client: only of its closed questions). */
    @Transactional(readOnly = true)
    Map<UUID, Answer> answers(List<Question> list) {
        var result = new HashMap<UUID, Answer>();
        answers.findByQuestionIdIn(list.stream().map(Question::getId).toList())
                .forEach(a -> result.put(a.getQuestionId(), a));
        return result;
    }

    @Transactional(readOnly = true)
    QuestionDetail detail(UUID projectId, UUID questionId) {
        var context = access.project(projectId);
        var question = access.question(context, questionId);
        var linked = answerDocuments.findByQuestionIdOrderByLinkedAt(questionId);
        var visible = new HashMap<UUID, DocumentSummary>();
        documents.findAll(projectId, linked.stream().map(AnswerDocument::getDocumentId).toList())
                .forEach(d -> visible.put(d.id(), d));
        var shown = linked.stream().filter(l -> visible.containsKey(l.getDocumentId()))
                .map(l -> new LinkedDocument(visible.get(l.getDocumentId()), l.getLinkedBy(), l.getLinkedAt()))
                .toList();
        return new QuestionDetail(question, answers.findById(questionId), shown, references(context, question),
                history.findByQuestionIdOrderByChangedAt(questionId));
    }

    private References references(ProjectContext context, Question q) {
        var projectId = context.projectId();
        var item = q.getRequestItemId() == null ? null : requestItems.find(projectId, q.getRequestItemId())
                .map(RequestItemDirectory.ItemSummary::number).orElse(null);
        var document = q.getDocumentId() == null ? null : documents.find(projectId, q.getDocumentId())
                .map(DocumentSummary::name).orElse(null);
        var finding = q.getFindingId() == null || !context.isInternal() ? null
                : findings.find(projectId, q.getFindingId()).map(FindingDirectory.FindingSummary::number).orElse(null);
        return new References(item, document, finding);
    }

    // ------------------------------------------------------------------ asking

    /**
     * The team drafts questions (or submits them straight for approval); the client's questions always go to
     * approval. Partners and project managers may send their own questions directly afterwards.
     */
    Question create(UUID projectId, QuestionCommand command, boolean submit) {
        var context = access.writableProject(projectId);
        QuestionStatus initial;
        if (QnaAccess.isEditor(context)) {
            initial = submit ? QuestionStatus.PENDING_APPROVAL : QuestionStatus.DRAFT;
        } else if (QnaAccess.isClient(context)) {
            initial = QuestionStatus.PENDING_APPROVAL;
        } else {
            throw new AccessDeniedException("Only the team and the client ask questions");
        }
        var userId = me();
        var question = new Question(projectId, questions.nextNumber(projectId), userId, initial);
        write(context, question, command);
        questions.saveAndFlush(question);
        history.save(new QuestionStatusChange(question, null, userId, question.getCreatedAt(), null));
        events.publishEvent(new QuestionChanged(projectId, question.ref(), "created", RequestMetadata.capture()));
        if (initial == QuestionStatus.PENDING_APPROVAL) {
            events.publishEvent(new QuestionStatusChanged(projectId, context.code(), question.ref(), null,
                    initial.name(), null, RequestMetadata.capture()));
        }
        return question;
    }

    Question update(UUID projectId, UUID questionId, QuestionCommand command) {
        var context = access.writableProject(projectId);
        requireEditor(context);
        var question = access.question(context, questionId);
        if (!question.getStatus().editable()) {
            throw new ResourceConflictException("Sent questions cannot be reworded");
        }
        write(context, question, command);
        events.publishEvent(new QuestionChanged(projectId, question.ref(), "updated",
                RequestMetadata.capture()));
        return question;
    }

    void delete(UUID projectId, UUID questionId) {
        var context = access.writableProject(projectId);
        requireEditor(context);
        var question = access.question(context, questionId);
        if (question.getStatus() != QuestionStatus.DRAFT || question.getSentAt() != null
                || !question.getAskedBy().equals(me()) && !QnaAccess.isManager(context)) {
            throw new ResourceConflictException("Only unsent drafts can be deleted, by their author or a manager");
        }
        questions.delete(question);
        events.publishEvent(new QuestionChanged(projectId, question.ref(), "deleted",
                RequestMetadata.capture()));
    }

    // ------------------------------------------------------------------ workflow

    /**
     * Team workflow: submit for approval, approve and send (partners and project managers), return with feedback,
     * close, or send back to the target with a follow-up. The target's answer has its own method.
     */
    Question changeStatus(UUID projectId, UUID questionId, QuestionStatus next, String feedback) {
        var context = access.writableProject(projectId);
        requireEditor(context);
        var question = access.question(context, questionId);
        var current = question.getStatus();
        var note = blankToNull(feedback);
        var allowed = switch (next) {
            case PENDING_APPROVAL -> current == QuestionStatus.DRAFT;
            case SENT -> {
                if (current == QuestionStatus.ANSWERED) {
                    yield true; // follow-up
                }
                if (current.editable() && !QnaAccess.isManager(context)) {
                    throw new AccessDeniedException("Only partners and project managers send questions to the target");
                }
                yield current.editable();
            }
            case DRAFT -> {
                if (!QnaAccess.isManager(context)) {
                    throw new AccessDeniedException("Only partners and project managers return questions");
                }
                yield current == QuestionStatus.PENDING_APPROVAL;
            }
            case CLOSED -> current == QuestionStatus.ANSWERED || current == QuestionStatus.SENT;
            case ANSWERED -> false;
        };
        if (!allowed) {
            throw new ResourceConflictException("A " + current + " question cannot become " + next);
        }
        var followUp = next == QuestionStatus.SENT && current == QuestionStatus.ANSWERED;
        var withdrawn = next == QuestionStatus.CLOSED && current == QuestionStatus.SENT;
        if ((next == QuestionStatus.DRAFT || followUp || withdrawn) && note == null) {
            throw new InvalidRequestException(followUp ? "Tell the target what is still missing"
                    : "Give a reason");
        }
        if (note != null && note.length() > Question.MAX_BODY) {
            throw new InvalidRequestException("The message is too long");
        }
        if (next == QuestionStatus.SENT && current.editable() && question.getWorkstreamId() == null
                && question.getRecipientUserId() == null && !hasAnyTarget(context)) {
            throw new ResourceConflictException("The project has no target-side member to send the question to");
        }
        return move(context, question, next, note);
    }

    /** The target's answer (also recorded by the team for answers received outside the platform). */
    Question answer(UUID projectId, UUID questionId, String body) {
        var context = access.writableProject(projectId);
        requireResponder(context);
        var question = access.question(context, questionId);
        if (!question.getStatus().answerable()) {
            throw new ResourceConflictException("This question is not open for an answer");
        }
        var text = blankToNull(body);
        if (text == null || text.length() > Answer.MAX_BODY) {
            throw new InvalidRequestException("Answers need between 1 and 10000 characters");
        }
        var userId = me();
        answers.findById(questionId).ifPresentOrElse(a -> a.revise(text, userId),
                () -> answers.save(new Answer(question, text, userId)));
        return question.getStatus() == QuestionStatus.ANSWERED ? touch(question)
                : move(context, question, QuestionStatus.ANSWERED, null);
    }

    void linkDocument(UUID projectId, UUID questionId, UUID documentId) {
        var context = access.writableProject(projectId);
        requireResponder(context);
        var question = access.question(context, questionId);
        if (!question.getStatus().answerable()) {
            throw new ResourceConflictException("Documents can only be added while the question is open");
        }
        var document = documents.find(projectId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        if (!answerDocuments.existsById(new AnswerDocument.Key(questionId, document.id()))) {
            answerDocuments.save(new AnswerDocument(question, document.id(), me()));
            events.publishEvent(new QuestionChanged(projectId, question.ref(),
                    "document-linked", RequestMetadata.capture()));
        }
    }

    void unlinkDocument(UUID projectId, UUID questionId, UUID documentId) {
        var context = access.writableProject(projectId);
        requireResponder(context);
        var question = access.question(context, questionId);
        if (!question.getStatus().answerable()) {
            throw new ResourceConflictException("Documents can only be changed while the question is open");
        }
        var link = answerDocuments.findById(new AnswerDocument.Key(questionId, documentId))
                .orElseThrow(() -> new ResourceNotFoundException("Linked document", documentId));
        if (QnaAccess.isTarget(context) && !link.getLinkedBy().equals(me())) {
            throw new AccessDeniedException("Only the person who added a document can remove it");
        }
        answerDocuments.delete(link);
        events.publishEvent(new QuestionChanged(projectId, question.ref(),
                "document-unlinked", RequestMetadata.capture()));
    }

    // ------------------------------------------------------------------ helpers

    private Question move(ProjectContext context, Question question, QuestionStatus next, String note) {
        var current = question.getStatus();
        var now = Instant.now(clock);
        var userId = me();
        question.moveTo(next, userId, now, note);
        history.save(new QuestionStatusChange(question, current, userId, now, note));
        events.publishEvent(new QuestionStatusChanged(context.projectId(), context.code(),
                question.ref(), current.name(), next.name(), note,
                RequestMetadata.capture()));
        return question;
    }

    private Question touch(Question question) {
        question.moveTo(question.getStatus(), me(), Instant.now(clock), question.getFeedback());
        return question;
    }

    private void write(ProjectContext context, Question question, QuestionCommand command) {
        var body = blankToNull(command.body());
        if (body == null || body.length() > Question.MAX_BODY) {
            throw new InvalidRequestException("Questions need between 1 and 4000 characters");
        }
        var links = command.links() == null ? new Question.Links(null, null, null, null) : command.links();
        var projectId = context.projectId();
        var workstreamId = links.workstreamId();
        if (links.requestItemId() != null) {
            var item = requestItems.find(projectId, links.requestItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Request item", links.requestItemId()));
            workstreamId = workstreamId == null ? item.workstreamId() : workstreamId;
        }
        if (links.findingId() != null) {
            if (!context.isInternal()) {
                throw new AccessDeniedException("Only the team links questions to findings");
            }
            var finding = findings.find(projectId, links.findingId())
                    .orElseThrow(() -> new ResourceNotFoundException("Finding", links.findingId()));
            workstreamId = workstreamId == null ? finding.workstreamId() : workstreamId;
        }
        if (links.documentId() != null && documents.find(projectId, links.documentId()).isEmpty()) {
            throw new ResourceNotFoundException("Document", links.documentId());
        }
        access.requireWorkstream(context, workstreamId);
        var recipient = command.recipientUserId();
        if (recipient != null) {
            var target = workstreamId;
            var eligible = projects.members(projectId).stream().anyMatch(m -> m.userId().equals(recipient)
                    && m.role() == ProjectRole.TARGET && m.covers(target));
            if (!eligible) {
                throw new InvalidRequestException("The recipient must be a target-side member of the workstream");
            }
        }
        question.write(body, new Question.Links(workstreamId, links.requestItemId(), links.documentId(),
                links.findingId()), recipient, command.dueDate());
    }

    private boolean hasAnyTarget(ProjectContext context) {
        return projects.members(context.projectId()).stream().anyMatch(m -> m.role() == ProjectRole.TARGET);
    }

    private static void requireEditor(ProjectContext context) {
        if (!QnaAccess.isEditor(context)) {
            throw new AccessDeniedException("Only the internal team manages questions");
        }
    }

    private static void requireResponder(ProjectContext context) {
        if (!QnaAccess.isTarget(context) && !QnaAccess.isEditor(context)) {
            throw new AccessDeniedException("Only the target side answers questions");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private UUID me() {
        return currentUser.requireCurrentUser().id();
    }
}
