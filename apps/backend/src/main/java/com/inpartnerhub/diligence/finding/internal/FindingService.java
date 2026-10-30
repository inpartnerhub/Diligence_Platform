package com.inpartnerhub.diligence.finding.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.dataroom.DocumentDirectory;
import com.inpartnerhub.diligence.dataroom.DocumentDirectory.DocumentSummary;
import com.inpartnerhub.diligence.finding.FindingEvents.FindingChanged;
import com.inpartnerhub.diligence.finding.FindingEvents.FindingEscalated;
import com.inpartnerhub.diligence.finding.FindingEvents.FindingStatusChanged;
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/**
 * Findings (PRD FND-01…FND-04): recorded by the team per workstream, rated with the configured risk matrix, linked to
 * their source documents and validated before the client sees them. Rules on top of the status workflow:
 * <ul>
 * <li>a finding is submitted for review only when it is rated and cites at least one source document;</li>
 * <li>four eyes: a reviewer (partner or project manager) other than the author approves;</li>
 * <li>only a partner releases to the client; released findings are final and can only be archived.</li>
 * </ul>
 */
@Service
@Transactional
class FindingService {

    static final Set<RiskLevel> ESCALATED = Set.of(RiskLevel.HIGH, RiskLevel.CRITICAL);

    record Rating(Integer impact, Integer likelihood) {
    }

    record FindingFilter(FindingStatus status, UUID workstreamId, RiskLevel level, String query) {

        static final FindingFilter ALL = new FindingFilter(null, null, null, null);

        boolean matches(Finding finding) {
            return (status == null || finding.getStatus() == status)
                    && (workstreamId == null || workstreamId.equals(finding.getWorkstreamId()))
                    && (level == null || level == finding.getRiskLevel())
                    && (query == null || query.isBlank() || contains(finding, query.strip().toLowerCase(Locale.ROOT)));
        }

        private static boolean contains(Finding finding, String needle) {
            return finding.getNumber().toLowerCase(Locale.ROOT).contains(needle)
                    || finding.getTitle().toLowerCase(Locale.ROOT).contains(needle)
                    || finding.getCategory().toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    record LinkedDocument(DocumentSummary document, String pageReference, String note, UUID linkedBy,
            Instant linkedAt) {
    }

    record FindingDetail(Finding finding, List<LinkedDocument> documents, int hiddenDocuments,
            List<FindingComment> comments, List<FindingStatusChange> history) {
    }

    private final FindingRepository findings;
    private final FindingDocumentRepository links;
    private final FindingStatusChangeRepository history;
    private final FindingCommentRepository comments;
    private final FindingAccess access;
    private final RiskMatrixService matrix;
    private final DocumentDirectory documents;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    FindingService(FindingRepository findings, FindingDocumentRepository links, FindingStatusChangeRepository history,
            FindingCommentRepository comments, FindingAccess access, RiskMatrixService matrix,
            DocumentDirectory documents, CurrentUserProvider currentUser, ApplicationEventPublisher events) {
        this(findings, links, history, comments, access, matrix, documents, currentUser, events,
                Clock.systemDefaultZone());
    }

    FindingService(FindingRepository findings, FindingDocumentRepository links, FindingStatusChangeRepository history,
            FindingCommentRepository comments, FindingAccess access, RiskMatrixService matrix,
            DocumentDirectory documents, CurrentUserProvider currentUser, ApplicationEventPublisher events,
            Clock clock) {
        this.findings = findings;
        this.links = links;
        this.history = history;
        this.comments = comments;
        this.access = access;
        this.matrix = matrix;
        this.documents = documents;
        this.currentUser = currentUser;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    /** Findings visible to the caller: the team (analysts: their workstreams) or, for clients, released ones. */
    @Transactional(readOnly = true)
    List<Finding> list(UUID projectId, FindingFilter filter) {
        var context = access.project(projectId);
        return findings.findByProjectIdOrderByNumber(projectId).stream()
                .filter(f -> !FindingAccess.isInternal(context) || context.coversWorkstream(f.getWorkstreamId()))
                .filter(filter::matches)
                .toList();
    }

    @Transactional(readOnly = true)
    Map<UUID, Long> documentCounts(UUID projectId) {
        var counts = new HashMap<UUID, Long>();
        for (var row : links.countsByFinding(projectId)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** A finding with its sources, review notes and history; for clients the working papers stay hidden (RLS). */
    @Transactional(readOnly = true)
    FindingDetail detail(UUID projectId, UUID findingId) {
        var context = access.project(projectId);
        var finding = access.finding(context, findingId);
        var linked = links.findByFindingIdOrderByLinkedAt(findingId);
        var visible = new HashMap<UUID, DocumentSummary>();
        documents.findAll(projectId, linked.stream().map(FindingDocument::getDocumentId).toList())
                .forEach(d -> visible.put(d.id(), d));
        var shown = linked.stream().filter(l -> visible.containsKey(l.getDocumentId()))
                .map(l -> new LinkedDocument(visible.get(l.getDocumentId()), l.getPageReference(), l.getNote(),
                        l.getLinkedBy(), l.getLinkedAt()))
                .toList();
        return new FindingDetail(finding, shown, linked.size() - shown.size(),
                comments.findByFindingIdOrderByCreatedAt(findingId),
                history.findByFindingIdOrderByChangedAt(findingId));
    }

    // ------------------------------------------------------------------ recording (DRAFT only)

    Finding create(UUID projectId, Finding.Content content, Rating rating) {
        var context = access.editableProject(projectId);
        var type = access.workstream(context, content.workstreamId());
        var number = FindingNumbers.next(type, findings.numbersWithPrefix(projectId, FindingNumbers.prefix(type)));
        var finding = new Finding(projectId, number, valid(content), me());
        var escalated = applyRating(finding, rating);
        findings.saveAndFlush(finding);
        history.save(new FindingStatusChange(finding, null, me(), finding.getCreatedAt(), null));
        events.publishEvent(new FindingChanged(projectId, finding.ref(), "created", RequestMetadata.capture()));
        escalate(context, finding, escalated);
        return finding;
    }

    Finding update(UUID projectId, UUID findingId, Finding.Content content, Rating rating) {
        var context = access.editableProject(projectId);
        var finding = editable(context, findingId);
        access.workstream(context, content.workstreamId());
        finding.update(valid(content));
        var escalated = applyRating(finding, rating);
        events.publishEvent(new FindingChanged(projectId, finding.ref(), "updated", RequestMetadata.capture()));
        escalate(context, finding, escalated);
        return finding;
    }

    /** Drafts that were never submitted can be deleted; everything else is archived instead. */
    void delete(UUID projectId, UUID findingId) {
        var context = access.editableProject(projectId);
        var finding = editable(context, findingId);
        if (finding.getSubmittedAt() != null) {
            throw new ResourceConflictException("Findings that were reviewed cannot be deleted; archive them instead");
        }
        findings.delete(finding);
        events.publishEvent(new FindingChanged(projectId, finding.ref(), "deleted", RequestMetadata.capture()));
    }

    // ------------------------------------------------------------------ workflow

    Finding changeStatus(UUID projectId, UUID findingId, FindingStatus next, String reason) {
        var context = access.editableProject(projectId);
        var finding = access.finding(context, findingId);
        var current = finding.getStatus();
        if (!current.mayMoveTo(next)) {
            throw new ResourceConflictException("A " + current + " finding cannot become " + next);
        }
        var userId = me();
        switch (next) {
            case IN_REVIEW -> {
                if (!finding.isRated()) {
                    throw new InvalidRequestException("Rate impact and likelihood before submitting for review");
                }
                if (!links.existsByFindingId(findingId)) {
                    throw new InvalidRequestException("Link at least one source document before submitting");
                }
            }
            case APPROVED -> {
                requireReviewer(context);
                if (userId.equals(finding.getCreatedBy())) {
                    throw new AccessDeniedException("Four eyes: the author cannot approve their own finding");
                }
            }
            case RELEASED -> {
                if (!FindingAccess.isPartner(context)) {
                    throw new AccessDeniedException("Only partners release findings to the client");
                }
            }
            case DRAFT -> requireReviewer(context);
            case ARCHIVED -> {
                if (current == FindingStatus.RELEASED ? !FindingAccess.isPartner(context)
                        : !FindingAccess.isReviewer(context)) {
                    throw new AccessDeniedException("Only partners and project managers archive findings");
                }
            }
        }
        var note = blankToNull(reason);
        if (current.needsReason(next) && note == null) {
            throw new InvalidRequestException("Give a reason");
        }
        if (note != null && note.length() > FindingComment.MAX_LENGTH) {
            throw new InvalidRequestException("The reason is too long");
        }
        var now = Instant.now(clock);
        finding.moveTo(next, userId, now, note);
        history.save(new FindingStatusChange(finding, current, userId, now, note));
        events.publishEvent(new FindingStatusChanged(projectId, context.code(), finding.ref(), current.name(),
                next.name(), note, RequestMetadata.capture()));
        return finding;
    }

    // ------------------------------------------------------------------ sources and review notes

    /** Links a data-room document the caller can see, with the page or section the finding relies on (FND-02). */
    void linkDocument(UUID projectId, UUID findingId, UUID documentId, String pageReference, String note) {
        var context = access.editableProject(projectId);
        var finding = editable(context, findingId);
        var document = documents.find(projectId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        var page = limited(pageReference, 100, "page reference");
        var text = limited(note, FindingComment.MAX_LENGTH, "note");
        links.findById(new FindingDocument.Key(findingId, document.id())).ifPresentOrElse(
                existing -> existing.change(page, text),
                () -> links.save(new FindingDocument(findingId, document.id(), projectId, page, text, me())));
        finding.touch();
        events.publishEvent(new FindingChanged(projectId, finding.ref(), "document-linked",
                RequestMetadata.capture()));
    }

    void unlinkDocument(UUID projectId, UUID findingId, UUID documentId) {
        var context = access.editableProject(projectId);
        var finding = editable(context, findingId);
        var link = links.findById(new FindingDocument.Key(findingId, documentId))
                .orElseThrow(() -> new ResourceNotFoundException("Linked document", documentId));
        links.delete(link);
        finding.touch();
        events.publishEvent(new FindingChanged(projectId, finding.ref(), "document-unlinked",
                RequestMetadata.capture()));
    }

    /** Internal review note (any status except archived); never visible to the client. */
    FindingComment comment(UUID projectId, UUID findingId, String body) {
        var context = access.editableProject(projectId);
        var finding = access.finding(context, findingId);
        if (finding.getStatus() == FindingStatus.ARCHIVED) {
            throw new ResourceConflictException("Archived findings take no further notes");
        }
        var text = blankToNull(body);
        if (text == null || text.length() > FindingComment.MAX_LENGTH) {
            throw new InvalidRequestException("Notes need between 1 and 4000 characters");
        }
        var saved = comments.save(new FindingComment(finding, me(), text));
        events.publishEvent(new FindingChanged(projectId, finding.ref(), "commented", RequestMetadata.capture()));
        return saved;
    }

    // ------------------------------------------------------------------ helpers

    private Finding editable(ProjectContext context, UUID findingId) {
        var finding = access.finding(context, findingId);
        if (!finding.getStatus().editable()) {
            throw new ResourceConflictException("Only draft findings can be changed; return it to draft first");
        }
        return finding;
    }

    /** @return whether the rating newly reached HIGH or CRITICAL (escalation, Appendix A) */
    private boolean applyRating(Finding finding, Rating rating) {
        var before = finding.getRiskLevel();
        if (rating == null || (rating.impact() == null && rating.likelihood() == null)) {
            finding.rate(null);
            return false;
        }
        if (rating.impact() == null || rating.likelihood() == null) {
            throw new InvalidRequestException("Rate both impact and likelihood, or neither");
        }
        try {
            finding.rate(matrix.current().rate(rating.impact(), rating.likelihood()));
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException(ex.getMessage());
        }
        var after = finding.getRiskLevel();
        return ESCALATED.contains(after) && (before == null || after.compareTo(before) > 0);
    }

    private void escalate(ProjectContext context, Finding finding, boolean escalated) {
        if (escalated) {
            events.publishEvent(new FindingEscalated(context.projectId(), context.code(), finding.ref(),
                    finding.getRiskLevel(), finding.getRiskScore(), RequestMetadata.capture()));
        }
    }

    private static void requireReviewer(ProjectContext context) {
        if (!FindingAccess.isReviewer(context)) {
            throw new AccessDeniedException("Only partners and project managers review findings");
        }
    }

    static Finding.Content valid(Finding.Content content) {
        var title = required(content.title(), Finding.MAX_TITLE, "title");
        var category = required(content.category(), Finding.MAX_CATEGORY, "category");
        var description = required(content.description(), Finding.MAX_TEXT, "description");
        var impactDescription = limited(content.impactDescription(), Finding.MAX_TEXT, "impact description");
        var recommendation = limited(content.recommendation(), Finding.MAX_TEXT, "recommendation");
        BigDecimal amount = content.monetaryImpact() == null ? null
                : content.monetaryImpact().setScale(2, RoundingMode.HALF_UP);
        String currency = null;
        if (amount != null) {
            if (amount.signum() < 0 || amount.precision() - amount.scale() > 17) {
                throw new InvalidRequestException("The monetary impact must be a positive amount");
            }
            currency = content.currency() == null || content.currency().isBlank() ? "IDR"
                    : content.currency().strip().toUpperCase(Locale.ROOT);
            if (!currency.matches("[A-Z]{3}")) {
                throw new InvalidRequestException("Currencies use ISO 4217 codes such as IDR or USD");
            }
        }
        return new Finding.Content(content.workstreamId(), title, category, description, impactDescription, amount,
                currency, recommendation);
    }

    private static String required(String value, int max, String name) {
        var text = limited(value, max, name);
        if (text == null) {
            throw new InvalidRequestException("The " + name + " is required");
        }
        return text;
    }

    private static String limited(String value, int max, String name) {
        var text = blankToNull(value);
        if (text != null && text.length() > max) {
            throw new InvalidRequestException("The " + name + " is too long");
        }
        return text;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private UUID me() {
        return currentUser.requireCurrentUser().id();
    }
}
