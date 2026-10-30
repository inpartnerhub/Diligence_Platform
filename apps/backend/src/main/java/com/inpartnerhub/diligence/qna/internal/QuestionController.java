package com.inpartnerhub.diligence.qna.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.shared.web.ApiPaths;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}/questions")
@Tag(name = "Q&A")
class QuestionController {

    record QuestionResponse(UUID id, String number, QuestionStatus status, String body, UUID workstreamId,
            UUID requestItemId, UUID documentId, UUID findingId, UUID askedBy, String askedByName,
            UUID recipientUserId, String recipientName, LocalDate dueDate, String feedback, Instant createdAt,
            Instant sentAt, Instant answeredAt, Instant closedAt, boolean answered) {
    }

    record AnswerResponse(String body, String answeredByName, Instant answeredAt) {
    }

    record DocumentResponse(UUID id, String name, String contentType, String scanStatus, String linkedByName,
            Instant linkedAt) {
    }

    record HistoryResponse(QuestionStatus fromStatus, QuestionStatus toStatus, String changedByName,
            Instant changedAt, String note) {
    }

    record ReferencesResponse(String requestItemNumber, String documentName, String findingNumber) {
    }

    record DetailResponse(QuestionResponse question, AnswerResponse answer, List<DocumentResponse> documents,
            ReferencesResponse references, List<HistoryResponse> history) {
    }

    record QuestionRequest(@NotBlank @Size(max = Question.MAX_BODY) String body, UUID workstreamId,
            UUID requestItemId, UUID documentId, UUID findingId, UUID recipientUserId, LocalDate dueDate,
            boolean submit) {

        QuestionService.QuestionCommand command() {
            return new QuestionService.QuestionCommand(body,
                    new Question.Links(workstreamId, requestItemId, documentId, findingId), recipientUserId, dueDate);
        }
    }

    record StatusRequest(@NotNull QuestionStatus status, @Size(max = Question.MAX_BODY) String feedback) {
    }

    record AnswerRequest(@NotBlank @Size(max = Answer.MAX_BODY) String body) {
    }

    record LinkRequest(@NotNull UUID documentId) {
    }

    private final QuestionService questions;
    private final QnaExportService exports;
    private final QnaAccess access;
    private final Directory directory;

    QuestionController(QuestionService questions, QnaExportService exports, QnaAccess access, Directory directory) {
        this.questions = questions;
        this.exports = exports;
        this.access = access;
        this.directory = directory;
    }

    @GetMapping
    @Operation(summary = "Questions visible to the caller (target: sent to it; client: its own), searchable — QNA-01, "
            + "QNA-03")
    List<QuestionResponse> list(@PathVariable UUID projectId, @RequestParam(required = false) QuestionStatus status,
            @RequestParam(required = false) UUID workstreamId, @RequestParam(required = false) String q) {
        var context = access.project(projectId);
        var list = questions.list(projectId, new QuestionService.QuestionFilter(status, workstreamId, q));
        var answers = questions.answers(list);
        var users = new HashSet<UUID>();
        list.forEach(x -> {
            users.add(x.getAskedBy());
            users.add(x.getRecipientUserId());
        });
        var names = names(users);
        return list.stream().map(x -> response(x, names, answers.containsKey(x.getId()), context))
                .toList();
    }

    @GetMapping("/{questionId}")
    @Operation(summary = "A question with the answer the caller may read, supporting documents and (team) history")
    DetailResponse detail(@PathVariable UUID projectId, @PathVariable UUID questionId) {
        var context = access.project(projectId);
        var detail = questions.detail(projectId, questionId);
        var q = detail.question();
        var users = new HashSet<UUID>();
        users.add(q.getAskedBy());
        users.add(q.getRecipientUserId());
        detail.answer().ifPresent(a -> users.add(a.getAnsweredBy()));
        detail.documents().forEach(d -> users.add(d.linkedBy()));
        detail.history().forEach(h -> users.add(h.getChangedBy()));
        var names = names(users);
        var refs = detail.references();
        return new DetailResponse(response(q, names, detail.answer().isPresent(), context),
                detail.answer().map(a -> new AnswerResponse(a.getBody(), names.get(a.getAnsweredBy()),
                        a.getAnsweredAt())).orElse(null),
                detail.documents().stream().map(d -> new DocumentResponse(d.document().id(), d.document().name(),
                        d.document().contentType(), d.document().scanStatus(), names.get(d.linkedBy()),
                        d.linkedAt())).toList(),
                new ReferencesResponse(refs.requestItemNumber(), refs.documentName(), refs.findingNumber()),
                detail.history().stream().map(h -> new HistoryResponse(h.getFromStatus(), h.getToStatus(),
                        names.get(h.getChangedBy()), h.getChangedAt(), h.getNote())).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask a question (team: draft or straight to approval; client: always to approval) — QNA-01")
    QuestionResponse create(@PathVariable UUID projectId, @Valid @RequestBody QuestionRequest request) {
        return single(projectId, questions.create(projectId, request.command(), request.submit()));
    }

    @PutMapping("/{questionId}")
    @Operation(summary = "Reword a question that was not sent yet (team)")
    QuestionResponse update(@PathVariable UUID projectId, @PathVariable UUID questionId,
            @Valid @RequestBody QuestionRequest request) {
        return single(projectId, questions.update(projectId, questionId, request.command()));
    }

    @DeleteMapping("/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an unsent draft")
    void delete(@PathVariable UUID projectId, @PathVariable UUID questionId) {
        questions.delete(projectId, questionId);
    }

    @PostMapping("/{questionId}/status")
    @Operation(summary = "Submit for approval, send (partners/PMs), return with feedback, follow up or close — QNA-02")
    QuestionResponse changeStatus(@PathVariable UUID projectId, @PathVariable UUID questionId,
            @Valid @RequestBody StatusRequest request) {
        return single(projectId, questions.changeStatus(projectId, questionId, request.status(), request.feedback()));
    }

    @PutMapping("/{questionId}/answer")
    @Operation(summary = "Answer a question that was sent (target) — QNA-01")
    QuestionResponse answer(@PathVariable UUID projectId, @PathVariable UUID questionId,
            @Valid @RequestBody AnswerRequest request) {
        return single(projectId, questions.answer(projectId, questionId, request.body()));
    }

    @PostMapping("/{questionId}/documents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Attach a data-room document to the answer")
    void link(@PathVariable UUID projectId, @PathVariable UUID questionId, @Valid @RequestBody LinkRequest request) {
        questions.linkDocument(projectId, questionId, request.documentId());
    }

    @DeleteMapping("/{questionId}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a document from the answer (the target only its own)")
    void unlink(@PathVariable UUID projectId, @PathVariable UUID questionId, @PathVariable UUID documentId) {
        questions.unlinkDocument(projectId, questionId, documentId);
    }

    @GetMapping("/export")
    @Operation(summary = "The Q&A log the caller can see as Excel (xlsx) or PDF; audited — QNA-03")
    ResponseEntity<byte[]> export(@PathVariable UUID projectId, @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) QuestionStatus status, @RequestParam(required = false) UUID workstreamId,
            @RequestParam(required = false) String q) {
        var parsed = switch (format.toLowerCase(Locale.ROOT)) {
            case "xlsx" -> QnaExportService.Format.XLSX;
            case "pdf" -> QnaExportService.Format.PDF;
            default -> throw new InvalidRequestException("Format must be xlsx or pdf");
        };
        var file = exports.export(projectId, new QuestionService.QuestionFilter(status, workstreamId, q), parsed);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    private QuestionResponse single(UUID projectId, Question q) {
        var context = access.project(projectId);
        var users = new HashSet<UUID>();
        users.add(q.getAskedBy());
        users.add(q.getRecipientUserId());
        return response(q, names(users), !questions.answers(List.of(q)).isEmpty(), context);
    }

    /**
     * The finding link is internal: the target and the client never learn which finding a question supports. The
     * client does not learn when the target answered before the team has closed the question either.
     */
    private static QuestionResponse response(Question q, Map<UUID, String> names, boolean answered,
            ProjectContext context) {
        var internal = context.isInternal();
        var hideAnswer = QnaAccess.isClient(context) && q.getStatus() != QuestionStatus.CLOSED;
        return new QuestionResponse(q.getId(), q.getNumber(), q.getStatus(), q.getBody(), q.getWorkstreamId(),
                q.getRequestItemId(), q.getDocumentId(), internal ? q.getFindingId() : null, q.getAskedBy(),
                names.get(q.getAskedBy()), q.getRecipientUserId(),
                q.getRecipientUserId() == null ? null : names.get(q.getRecipientUserId()), q.getDueDate(),
                q.getFeedback(), q.getCreatedAt(), q.getSentAt(), hideAnswer ? null : q.getAnsweredAt(),
                q.getClosedAt(), answered);
    }

    private Map<UUID, String> names(Collection<UUID> ids) {
        var names = new HashMap<UUID, String>();
        var wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (!wanted.isEmpty()) {
            directory.findUsers(wanted).forEach(u -> names.putIfAbsent(u.id(),
                    u.displayName() == null || u.displayName().isBlank() ? u.email() : u.displayName()));
        }
        return names;
    }
}
