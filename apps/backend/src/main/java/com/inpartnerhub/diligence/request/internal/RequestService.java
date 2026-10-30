package com.inpartnerhub.diligence.request.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.dataroom.DocumentDirectory;
import com.inpartnerhub.diligence.dataroom.DocumentDirectory.DocumentSummary;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectCreated;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.request.RequestEvents.ItemRef;
import com.inpartnerhub.diligence.request.RequestEvents.RequestCommentAdded;
import com.inpartnerhub.diligence.request.RequestEvents.RequestDocumentLinked;
import com.inpartnerhub.diligence.request.RequestEvents.RequestItemChanged;
import com.inpartnerhub.diligence.request.RequestEvents.RequestItemsReleased;
import com.inpartnerhub.diligence.request.RequestEvents.RequestStatusChanged;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/**
 * Request-list workflow (PRD REQ-01…REQ-03, REQ-06): items are prepared by the internal team, released to the target
 * in waves, answered by the target with data-room documents and comments, and closed or rejected by the team. Every
 * status change is recorded in the item's history and published as an audited event that also drives notifications.
 */
@Service
@Transactional
class RequestService {

    record ItemCommand(UUID workstreamId, String number, String category, String description,
            RequestPriority priority, LocalDate dueDate, Integer wave, UUID assigneeUserId) {
    }

    record ItemFilter(RequestStatus status, UUID workstreamId, Integer wave, String query) {

        static final ItemFilter ALL = new ItemFilter(null, null, null, null);

        boolean matches(RequestItem item) {
            return (status == null || item.getStatus() == status)
                    && (workstreamId == null || workstreamId.equals(item.getWorkstreamId()))
                    && (wave == null || wave == item.getWave())
                    && (query == null || query.isBlank() || contains(item, query.strip().toLowerCase(Locale.ROOT)));
        }

        private static boolean contains(RequestItem item, String needle) {
            return item.getNumber().toLowerCase(Locale.ROOT).contains(needle)
                    || item.getCategory().toLowerCase(Locale.ROOT).contains(needle)
                    || item.getDescription().toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    record LinkedDocument(DocumentSummary document, String comment, UUID linkedBy, Instant linkedAt) {
    }

    record ItemDetail(RequestItem item, List<LinkedDocument> documents, int hiddenDocuments,
            List<RequestComment> comments, List<RequestStatusChange> history) {
    }

    private final RequestItemRepository items;
    private final RequestItemDocumentRepository links;
    private final RequestStatusChangeRepository history;
    private final RequestCommentRepository comments;
    private final RequestAccess access;
    private final ProjectDirectory projects;
    private final DocumentDirectory documents;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    RequestService(RequestItemRepository items, RequestItemDocumentRepository links,
            RequestStatusChangeRepository history, RequestCommentRepository comments, RequestAccess access,
            ProjectDirectory projects, DocumentDirectory documents, CurrentUserProvider currentUser,
            ApplicationEventPublisher events) {
        this(items, links, history, comments, access, projects, documents, currentUser, events,
                Clock.systemDefaultZone());
    }

    RequestService(RequestItemRepository items, RequestItemDocumentRepository links,
            RequestStatusChangeRepository history, RequestCommentRepository comments, RequestAccess access,
            ProjectDirectory projects, DocumentDirectory documents, CurrentUserProvider currentUser,
            ApplicationEventPublisher events, Clock clock) {
        this.items = items;
        this.links = links;
        this.history = history;
        this.comments = comments;
        this.access = access;
        this.projects = projects;
        this.documents = documents;
        this.currentUser = currentUser;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    /** Items visible to the caller (row-level security hides unreleased items from the target), by number. */
    @Transactional(readOnly = true)
    List<RequestItem> list(UUID projectId, ItemFilter filter) {
        var context = access.project(projectId);
        return items.findByProjectIdOrderByNumber(projectId).stream()
                .filter(i -> context.coversWorkstream(i.getWorkstreamId()))
                .filter(filter::matches)
                .toList();
    }

    @Transactional(readOnly = true)
    Map<UUID, Long> documentCounts(UUID projectId) {
        var counts = new HashMap<UUID, Long>();
        for (var row : links.countsByItem(projectId)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** One item with its documents (only those the caller may open), comments and history. */
    @Transactional(readOnly = true)
    ItemDetail detail(UUID projectId, UUID itemId) {
        var context = access.project(projectId);
        var item = access.item(context, itemId);
        var linked = links.findByRequestItemIdOrderByLinkedAt(itemId);
        var visible = new HashMap<UUID, DocumentSummary>();
        documents.findAll(projectId, linked.stream().map(RequestItemDocument::getDocumentId).toList())
                .forEach(d -> visible.put(d.id(), d));
        var shown = linked.stream().filter(l -> visible.containsKey(l.getDocumentId()))
                .map(l -> new LinkedDocument(visible.get(l.getDocumentId()), l.getComment(), l.getLinkedBy(),
                        l.getLinkedAt()))
                .toList();
        return new ItemDetail(item, shown, linked.size() - shown.size(),
                comments.findByRequestItemIdOrderByCreatedAt(itemId),
                history.findByRequestItemIdOrderByChangedAt(itemId));
    }

    // ------------------------------------------------------------------ maintaining the list (internal team)

    RequestItem create(UUID projectId, ItemCommand command) {
        var context = access.editableProject(projectId);
        var type = access.workstream(context, command.workstreamId());
        var number = blankToNull(command.number());
        if (number == null) {
            number = RequestNumbers.next(type, items.numbersWithPrefix(projectId, RequestNumbers.prefix(type)));
        } else {
            number = validNumber(number);
            if (items.findByProjectIdAndNumber(projectId, number).isPresent()) {
                throw new ResourceConflictException("Request number " + number + " already exists");
            }
        }
        var item = new RequestItem(projectId, command.workstreamId(), number, validCategory(command.category()),
                validDescription(command.description()), command.priority(), command.dueDate(),
                validWave(command.wave()));
        item.assign(validAssignee(context, item.getWorkstreamId(), command.assigneeUserId()));
        items.saveAndFlush(item);
        history.save(new RequestStatusChange(item, null, me(), item.getCreatedAt(), null));
        events.publishEvent(new RequestItemChanged(projectId, item.ref(), "created", RequestMetadata.capture()));
        return item;
    }

    /** Changes the content of an item; numbers never change and status has its own workflow. */
    RequestItem update(UUID projectId, UUID itemId, ItemCommand command) {
        var context = access.editableProject(projectId);
        var item = access.item(context, itemId);
        var workstreamId = command.workstreamId() == null ? item.getWorkstreamId() : command.workstreamId();
        access.workstream(context, workstreamId);
        item.update(workstreamId,
                command.category() == null ? item.getCategory() : validCategory(command.category()),
                command.description() == null ? item.getDescription() : validDescription(command.description()),
                command.priority() == null ? item.getPriority() : command.priority(),
                command.dueDate(),
                command.wave() == null ? item.getWave() : validWave(command.wave()));
        item.assign(validAssignee(context, workstreamId, command.assigneeUserId()));
        events.publishEvent(new RequestItemChanged(projectId, item.ref(), "updated", RequestMetadata.capture()));
        return item;
    }

    /** Only items the target has never seen can be deleted; released ones are closed instead (history). */
    void delete(UUID projectId, UUID itemId) {
        var context = access.editableProject(projectId);
        var item = access.item(context, itemId);
        if (item.getStatus() != RequestStatus.NOT_REQUESTED || item.getRequestedAt() != null) {
            throw new ResourceConflictException("Released items cannot be deleted; close them instead");
        }
        items.delete(item);
        events.publishEvent(new RequestItemChanged(projectId, item.ref(), "deleted", RequestMetadata.capture()));
    }

    /**
     * Releases the not-yet-requested items of a wave (optionally of one workstream) to the target (PRD REQ-06).
     *
     * @return the released items
     */
    List<RequestItem> release(UUID projectId, int wave, UUID workstreamId) {
        var context = access.manageableProject(projectId);
        if (workstreamId != null) {
            access.workstream(context, workstreamId);
        }
        var now = Instant.now(clock);
        var userId = me();
        var released = items.findByProjectIdAndStatusAndWave(projectId, RequestStatus.NOT_REQUESTED, (short) wave)
                .stream()
                .filter(i -> workstreamId == null || workstreamId.equals(i.getWorkstreamId()))
                .toList();
        for (var item : released) {
            item.moveTo(RequestStatus.REQUESTED, now);
            history.save(new RequestStatusChange(item, RequestStatus.NOT_REQUESTED, userId, now, null));
        }
        if (!released.isEmpty()) {
            events.publishEvent(new RequestItemsReleased(projectId, context.code(), wave,
                    released.stream().map(RequestItem::ref).toList(), RequestMetadata.capture()));
        }
        return released;
    }

    // ------------------------------------------------------------------ workflow (internal team and target)

    /**
     * Moves an item along the workflow ({@link RequestStatus}). The target reports PARTIAL or RECEIVED delivery
     * once documents are linked; the internal team may also reject (with a reason the target sees) or close.
     */
    RequestItem changeStatus(UUID projectId, UUID itemId, RequestStatus next, String reason) {
        var context = access.writableProject(projectId);
        var item = access.item(context, itemId);
        var current = item.getStatus();
        var byTarget = RequestAccess.isTarget(context);
        if (byTarget) {
            if (!current.targetMayMoveTo(next)) {
                throw new ResourceConflictException("The item cannot be marked " + next + " now");
            }
            if (!links.existsByRequestItemId(itemId)) {
                throw new InvalidRequestException("Link at least one document before submitting");
            }
        } else {
            if (!RequestAccess.isEditor(context)) {
                throw new AccessDeniedException("Observers cannot change requests");
            }
            if (!current.internalMayMoveTo(next)) {
                throw new ResourceConflictException("A " + current + " item cannot become " + next);
            }
            if (next == RequestStatus.NOT_REQUESTED && links.existsByRequestItemId(itemId)) {
                throw new ResourceConflictException("Items with delivered documents cannot be withdrawn");
            }
            if (next == RequestStatus.REQUESTED && current == RequestStatus.NOT_REQUESTED && !context.canManage()) {
                throw new AccessDeniedException("Only partners and project managers release requests");
            }
        }
        var note = blankToNull(reason);
        if (next == RequestStatus.REJECTED && note == null) {
            throw new InvalidRequestException("Tell the target why the delivery is rejected");
        }
        if (note != null && note.length() > RequestComment.MAX_LENGTH) {
            throw new InvalidRequestException("The reason is too long");
        }

        var now = Instant.now(clock);
        var userId = me();
        item.moveTo(next, now);
        history.save(new RequestStatusChange(item, current, userId, now, note));
        if (note != null && next == RequestStatus.REJECTED) {
            comments.save(new RequestComment(item, userId, note, false));
        }
        var metadata = RequestMetadata.capture();
        if (current == RequestStatus.NOT_REQUESTED && next == RequestStatus.REQUESTED) {
            events.publishEvent(new RequestItemsReleased(projectId, context.code(), item.getWave(),
                    List.of(item.ref()), metadata));
        }
        events.publishEvent(new RequestStatusChanged(projectId, context.code(), item.ref(), current.name(),
                next.name(), byTarget, next == RequestStatus.REJECTED ? note : null, metadata));
        return item;
    }

    /** Links a data-room document the caller can see to an item (PRD REQ-03). */
    void linkDocument(UUID projectId, UUID itemId, UUID documentId, String comment) {
        var context = access.writableProject(projectId);
        var item = access.item(context, itemId);
        requireDeliveryAllowed(context, item);
        var document = documents.find(projectId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        var key = new RequestItemDocument.Key(itemId, document.id());
        if (links.existsById(key)) {
            return;
        }
        var note = blankToNull(comment);
        if (note != null && note.length() > RequestComment.MAX_LENGTH) {
            throw new InvalidRequestException("The note is too long");
        }
        links.save(new RequestItemDocument(itemId, document.id(), projectId, note, me()));
        item.touch();
        events.publishEvent(new RequestDocumentLinked(projectId, item.ref(), documentId, true,
                RequestMetadata.capture()));
    }

    /** The target may only remove its own links; the internal team any link. */
    void unlinkDocument(UUID projectId, UUID itemId, UUID documentId) {
        var context = access.writableProject(projectId);
        var item = access.item(context, itemId);
        requireDeliveryAllowed(context, item);
        var link = links.findById(new RequestItemDocument.Key(itemId, documentId))
                .orElseThrow(() -> new ResourceNotFoundException("Linked document", documentId));
        if (RequestAccess.isTarget(context) && !link.getLinkedBy().equals(me())) {
            throw new AccessDeniedException("Only the person who linked a document can remove it");
        }
        links.delete(link);
        item.touch();
        events.publishEvent(new RequestDocumentLinked(projectId, item.ref(), documentId, false,
                RequestMetadata.capture()));
    }

    /** Adds a comment; internal notes are for the internal team only and never reach the target (REQ-03). */
    RequestComment comment(UUID projectId, UUID itemId, String body, boolean internal) {
        var context = access.writableProject(projectId);
        var item = access.item(context, itemId);
        var byTarget = RequestAccess.isTarget(context);
        if (!byTarget && !RequestAccess.isEditor(context)) {
            throw new AccessDeniedException("Observers cannot comment");
        }
        if (byTarget && internal) {
            throw new AccessDeniedException("Internal notes are for the internal team");
        }
        var text = blankToNull(body);
        if (text == null || text.length() > RequestComment.MAX_LENGTH) {
            throw new InvalidRequestException("Comments need between 1 and 4000 characters");
        }
        var saved = comments.save(new RequestComment(item, me(), text, internal));
        events.publishEvent(new RequestCommentAdded(projectId, context.code(), item.ref(), saved.getId(),
                saved.getAuthorId(), byTarget, internal, RequestMetadata.capture()));
        return saved;
    }

    // ------------------------------------------------------------------ template

    /** Creates the template's request list for a new project (PRD REQ-01), inside the creation transaction. */
    @EventListener
    void onProjectCreated(ProjectCreated event) {
        var used = new HashMap<String, List<String>>();
        for (var entry : event.requestItems()) {
            if (entry.description() == null || entry.description().isBlank()) {
                continue;
            }
            var prefix = RequestNumbers.prefix(entry.workstream());
            var numbers = used.computeIfAbsent(prefix, p -> new ArrayList<>());
            var number = RequestNumbers.next(entry.workstream(), numbers);
            numbers.add(number);
            var item = new RequestItem(event.projectId(), entry.workstreamId(), number,
                    truncate(entry.category(), RequestItem.MAX_CATEGORY),
                    truncate(entry.description(), RequestItem.MAX_DESCRIPTION), RequestPriority.MEDIUM, null, 1);
            items.save(item);
            history.save(new RequestStatusChange(item, null, event.requestMetadata().actorId(),
                    item.getCreatedAt(), "template"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void requireDeliveryAllowed(ProjectContext context, RequestItem item) {
        if (!RequestAccess.isTarget(context) && !RequestAccess.isEditor(context)) {
            throw new AccessDeniedException("Observers cannot change requests");
        }
        if (!item.getStatus().acceptsDeliveries()) {
            throw new ResourceConflictException("Documents can only be changed on released, open items");
        }
    }

    /** The person in charge on the target side: a target member covering the item's workstream. */
    private UUID validAssignee(ProjectContext context, UUID workstreamId, UUID assigneeUserId) {
        if (assigneeUserId == null) {
            return null;
        }
        var eligible = projects.members(context.projectId()).stream()
                .anyMatch(m -> m.userId().equals(assigneeUserId) && m.role() == ProjectRole.TARGET
                        && m.covers(workstreamId));
        if (!eligible) {
            throw new InvalidRequestException("The assignee must be a target-side member of this workstream");
        }
        return assigneeUserId;
    }

    static String validNumber(String number) {
        var value = number.strip();
        if (!RequestNumbers.isValid(value)) {
            throw new InvalidRequestException("Request numbers use letters, digits, '.', '-' or '_' (max. 32)");
        }
        return value;
    }

    static String validCategory(String category) {
        var value = blankToNull(category);
        if (value != null && value.length() > RequestItem.MAX_CATEGORY) {
            throw new InvalidRequestException("The category is too long");
        }
        return value;
    }

    static String validDescription(String description) {
        var value = blankToNull(description);
        if (value == null) {
            throw new InvalidRequestException("Request items need a description");
        }
        if (value.length() > RequestItem.MAX_DESCRIPTION) {
            throw new InvalidRequestException("The description is too long");
        }
        return value;
    }

    static int validWave(Integer wave) {
        var value = wave == null ? 1 : wave;
        if (value < 1 || value > RequestItem.MAX_WAVE) {
            throw new InvalidRequestException("Waves are numbered 1 to " + RequestItem.MAX_WAVE);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value, int max) {
        var cleaned = blankToNull(value);
        return cleaned == null || cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private UUID me() {
        return currentUser.requireCurrentUser().id();
    }
}
