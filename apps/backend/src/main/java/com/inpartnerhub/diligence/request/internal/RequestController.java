package com.inpartnerhub.diligence.request.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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
import com.inpartnerhub.diligence.dataroom.DocumentDirectory.DocumentSummary;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}/requests")
@Tag(name = "Request list")
class RequestController {

    record ItemResponse(UUID id, String number, UUID workstreamId, WorkstreamType workstream, String category,
            String description, RequestPriority priority, LocalDate dueDate, RequestStatus status,
            UUID assigneeUserId, String assigneeName, int wave, Instant requestedAt, Instant updatedAt,
            long documentCount) {
    }

    record DocumentResponse(UUID id, UUID folderId, String name, String contentType, long sizeBytes,
            String scanStatus, String comment, UUID linkedBy, String linkedByName, Instant linkedAt) {
    }

    record CommentResponse(UUID id, UUID authorId, String authorName, String body, boolean internal,
            Instant createdAt) {
    }

    record HistoryResponse(RequestStatus fromStatus, RequestStatus toStatus, int wave, UUID changedBy,
            String changedByName, Instant changedAt, String note) {
    }

    record ItemDetailResponse(ItemResponse item, List<DocumentResponse> documents, int hiddenDocuments,
            List<CommentResponse> comments, List<HistoryResponse> history) {
    }

    record ItemRequest(@NotNull UUID workstreamId, @Size(max = 32) String number,
            @Size(max = RequestItem.MAX_CATEGORY) String category,
            @NotBlank @Size(max = RequestItem.MAX_DESCRIPTION) String description, RequestPriority priority,
            LocalDate dueDate, @Min(1) @Max(RequestItem.MAX_WAVE) Integer wave, UUID assigneeUserId) {

        RequestService.ItemCommand command() {
            return new RequestService.ItemCommand(workstreamId, number, category, description, priority, dueDate,
                    wave, assigneeUserId);
        }
    }

    record ReleaseRequest(@NotNull @Min(1) @Max(RequestItem.MAX_WAVE) Integer wave, UUID workstreamId) {
    }

    record ReleaseResponse(int released) {
    }

    record StatusRequest(@NotNull RequestStatus status, @Size(max = RequestComment.MAX_LENGTH) String reason) {
    }

    record LinkRequest(@NotNull UUID documentId, @Size(max = RequestComment.MAX_LENGTH) String comment) {
    }

    record CommentRequest(@NotBlank @Size(max = RequestComment.MAX_LENGTH) String body, boolean internal) {
    }

    private final RequestService requests;
    private final RequestAccess access;
    private final Directory directory;

    RequestController(RequestService requests, RequestAccess access, Directory directory) {
        this.requests = requests;
        this.access = access;
        this.directory = directory;
    }

    @GetMapping
    @Operation(summary = "Request items visible to the caller (target: released items of its workstreams), "
            + "filtered by status, workstream, wave or text — REQ-01, REQ-02")
    List<ItemResponse> list(@PathVariable UUID projectId, @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) UUID workstreamId, @RequestParam(required = false) Integer wave,
            @RequestParam(required = false) String q) {
        var items = requests.list(projectId, new RequestService.ItemFilter(status, workstreamId, wave, q));
        var counts = requests.documentCounts(projectId);
        var types = access.workstreams(projectId);
        var names = names(items.stream().map(RequestItem::getAssigneeUserId).toList());
        return items.stream().map(i -> item(i, types, names, counts.getOrDefault(i.getId(), 0L))).toList();
    }

    @GetMapping("/{itemId}")
    @Operation(summary = "An item with its documents, comments (target: no internal notes) and status history "
            + "— REQ-02, REQ-03, REQ-06")
    ItemDetailResponse detail(@PathVariable UUID projectId, @PathVariable UUID itemId) {
        return detailResponse(projectId, requests.detail(projectId, itemId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an item; without a number the next one of the workstream is used (LEG-004) — REQ-01")
    ItemResponse create(@PathVariable UUID projectId, @Valid @RequestBody ItemRequest request) {
        return single(projectId, requests.create(projectId, request.command()));
    }

    @PutMapping("/{itemId}")
    @Operation(summary = "Replace an item's content (number and status stay) — REQ-01")
    ItemResponse update(@PathVariable UUID projectId, @PathVariable UUID itemId,
            @Valid @RequestBody ItemRequest request) {
        return single(projectId, requests.update(projectId, itemId, request.command()));
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an item that was never released")
    void delete(@PathVariable UUID projectId, @PathVariable UUID itemId) {
        requests.delete(projectId, itemId);
    }

    @PostMapping("/release")
    @Operation(summary = "Release the unreleased items of a wave (optionally one workstream) to the target "
            + "— REQ-06")
    ReleaseResponse release(@PathVariable UUID projectId, @Valid @RequestBody ReleaseRequest request) {
        return new ReleaseResponse(requests.release(projectId, request.wave(), request.workstreamId()).size());
    }

    @PostMapping("/{itemId}/status")
    @Operation(summary = "Move an item along the workflow; the target reports PARTIAL or RECEIVED, the team may "
            + "reject with a reason or close — REQ-02")
    ItemResponse changeStatus(@PathVariable UUID projectId, @PathVariable UUID itemId,
            @Valid @RequestBody StatusRequest request) {
        return single(projectId, requests.changeStatus(projectId, itemId, request.status(), request.reason()));
    }

    @PostMapping("/{itemId}/documents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Link a data-room document to an item — REQ-03")
    void link(@PathVariable UUID projectId, @PathVariable UUID itemId, @Valid @RequestBody LinkRequest request) {
        requests.linkDocument(projectId, itemId, request.documentId(), request.comment());
    }

    @DeleteMapping("/{itemId}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a document link (the target only its own) — REQ-03")
    void unlink(@PathVariable UUID projectId, @PathVariable UUID itemId, @PathVariable UUID documentId) {
        requests.unlinkDocument(projectId, itemId, documentId);
    }

    @PostMapping("/{itemId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Comment on an item; internal notes are never shown to the target — REQ-03")
    CommentResponse comment(@PathVariable UUID projectId, @PathVariable UUID itemId,
            @Valid @RequestBody CommentRequest request) {
        var comment = requests.comment(projectId, itemId, request.body(), request.internal());
        var names = names(List.of(comment.getAuthorId()));
        return new CommentResponse(comment.getId(), comment.getAuthorId(), names.get(comment.getAuthorId()),
                comment.getBody(), comment.isInternal(), comment.getCreatedAt());
    }

    private ItemResponse single(UUID projectId, RequestItem item) {
        var names = names(Collections.singletonList(item.getAssigneeUserId()));
        return item(item, access.workstreams(projectId), names,
                requests.documentCounts(projectId).getOrDefault(item.getId(), 0L));
    }

    private ItemDetailResponse detailResponse(UUID projectId, RequestService.ItemDetail detail) {
        var users = new HashSet<UUID>();
        users.add(detail.item().getAssigneeUserId());
        detail.documents().forEach(d -> users.add(d.linkedBy()));
        detail.comments().forEach(c -> users.add(c.getAuthorId()));
        detail.history().forEach(h -> users.add(h.getChangedBy()));
        var names = names(users);
        var item = item(detail.item(), access.workstreams(projectId), names, detail.documents().size()
                + detail.hiddenDocuments());
        return new ItemDetailResponse(item,
                detail.documents().stream().map(d -> document(d, names)).toList(),
                detail.hiddenDocuments(),
                detail.comments().stream().map(c -> new CommentResponse(c.getId(), c.getAuthorId(),
                        names.get(c.getAuthorId()), c.getBody(), c.isInternal(), c.getCreatedAt())).toList(),
                detail.history().stream().map(h -> new HistoryResponse(h.getFromStatus(), h.getToStatus(), h.getWave(),
                        h.getChangedBy(), names.get(h.getChangedBy()), h.getChangedAt(), h.getNote())).toList());
    }

    private static DocumentResponse document(RequestService.LinkedDocument linked, Map<UUID, String> names) {
        DocumentSummary d = linked.document();
        return new DocumentResponse(d.id(), d.folderId(), d.name(), d.contentType(), d.sizeBytes(), d.scanStatus(),
                linked.comment(), linked.linkedBy(), names.get(linked.linkedBy()), linked.linkedAt());
    }

    private static ItemResponse item(RequestItem i, Map<UUID, WorkstreamType> types, Map<UUID, String> names,
            long documentCount) {
        return new ItemResponse(i.getId(), i.getNumber(), i.getWorkstreamId(), types.get(i.getWorkstreamId()),
                i.getCategory(), i.getDescription(), i.getPriority(), i.getDueDate(), i.getStatus(),
                i.getAssigneeUserId(), i.getAssigneeUserId() == null ? null : names.get(i.getAssigneeUserId()),
                i.getWave(), i.getRequestedAt(), i.getUpdatedAt(), documentCount);
    }

    /** Display names for user ids; null ids (system actions) are ignored, and looking them up yields null. */
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
