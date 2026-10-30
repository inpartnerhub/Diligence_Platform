package com.inpartnerhub.diligence.finding.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.finding.RiskMatrix;
import com.inpartnerhub.diligence.project.ProjectDirectory;
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
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}/findings")
@Tag(name = "Findings")
class FindingController {

    record FindingResponse(UUID id, String number, UUID workstreamId, WorkstreamType workstream, String title,
            String category, String description, String impactDescription, BigDecimal monetaryImpact,
            String currency, String recommendation, Integer impact, Integer likelihood, Integer riskScore,
            RiskLevel riskLevel, FindingStatus status, UUID createdBy, String createdByName, Instant createdAt,
            Instant updatedAt, Instant submittedAt, Instant approvedAt, String approvedByName, Instant releasedAt,
            String releasedByName, String archiveReason, long documentCount) {
    }

    record DocumentResponse(UUID id, UUID folderId, String name, String contentType, String scanStatus,
            String pageReference, String note, String linkedByName, Instant linkedAt) {
    }

    record CommentResponse(UUID id, UUID authorId, String authorName, String body, Instant createdAt) {
    }

    record HistoryResponse(FindingStatus fromStatus, FindingStatus toStatus, String changedByName, Instant changedAt,
            String note) {
    }

    record DetailResponse(FindingResponse finding, List<DocumentResponse> documents, int hiddenDocuments,
            List<CommentResponse> comments, List<HistoryResponse> history) {
    }

    record FindingRequest(@NotNull UUID workstreamId, @NotBlank @Size(max = Finding.MAX_TITLE) String title,
            @NotBlank @Size(max = Finding.MAX_CATEGORY) String category,
            @NotBlank @Size(max = Finding.MAX_TEXT) String description,
            @Size(max = Finding.MAX_TEXT) String impactDescription, BigDecimal monetaryImpact,
            @Size(max = 3) String currency, @Size(max = Finding.MAX_TEXT) String recommendation,
            @Min(RiskMatrix.MIN_SCALE) @Max(RiskMatrix.MAX_SCALE) Integer impact,
            @Min(RiskMatrix.MIN_SCALE) @Max(RiskMatrix.MAX_SCALE) Integer likelihood) {

        Finding.Content content() {
            return new Finding.Content(workstreamId, title, category, description, impactDescription, monetaryImpact,
                    currency, recommendation);
        }

        FindingService.Rating rating() {
            return new FindingService.Rating(impact, likelihood);
        }
    }

    record StatusRequest(@NotNull FindingStatus status, @Size(max = FindingComment.MAX_LENGTH) String reason) {
    }

    record LinkRequest(@NotNull UUID documentId, @Size(max = 100) String pageReference,
            @Size(max = FindingComment.MAX_LENGTH) String note) {
    }

    record CommentRequest(@NotBlank @Size(max = FindingComment.MAX_LENGTH) String body) {
    }

    record CategoryResponse(UUID workstreamId, WorkstreamType workstream, String name) {
    }

    private final FindingService findings;
    private final FindingAccess access;
    private final ProjectDirectory projects;
    private final Directory directory;

    FindingController(FindingService findings, FindingAccess access, ProjectDirectory projects, Directory directory) {
        this.findings = findings;
        this.access = access;
        this.projects = projects;
        this.directory = directory;
    }

    @GetMapping
    @Operation(summary = "Findings visible to the caller (clients: released only), filtered by status, workstream, "
            + "risk level or text — FND-01, FND-04")
    List<FindingResponse> list(@PathVariable UUID projectId, @RequestParam(required = false) FindingStatus status,
            @RequestParam(required = false) UUID workstreamId, @RequestParam(required = false) RiskLevel level,
            @RequestParam(required = false) String q) {
        var list = findings.list(projectId, new FindingService.FindingFilter(status, workstreamId, level, q));
        var counts = findings.documentCounts(projectId);
        var types = access.workstreams(projectId);
        var users = new HashSet<UUID>();
        list.forEach(f -> users.addAll(people(f)));
        var names = names(users);
        return list.stream().map(f -> response(f, types, names, counts.getOrDefault(f.getId(), 0L))).toList();
    }

    @GetMapping("/categories")
    @Operation(summary = "Finding categories from the project template, per workstream — FND-01")
    List<CategoryResponse> categories(@PathVariable UUID projectId) {
        access.project(projectId);
        return projects.findingCategories(projectId).stream()
                .map(c -> new CategoryResponse(c.workstreamId(), c.workstream(), c.name())).toList();
    }

    @GetMapping("/{findingId}")
    @Operation(summary = "A finding with its source documents, review notes and history (internal team) — FND-02")
    DetailResponse detail(@PathVariable UUID projectId, @PathVariable UUID findingId) {
        return detailResponse(projectId, findings.detail(projectId, findingId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a finding (draft), optionally rated impact × likelihood — FND-01, FND-03")
    FindingResponse create(@PathVariable UUID projectId, @Valid @RequestBody FindingRequest request) {
        return single(projectId, findings.create(projectId, request.content(), request.rating()));
    }

    @PutMapping("/{findingId}")
    @Operation(summary = "Replace the content and rating of a draft finding — FND-01, FND-03")
    FindingResponse update(@PathVariable UUID projectId, @PathVariable UUID findingId,
            @Valid @RequestBody FindingRequest request) {
        return single(projectId, findings.update(projectId, findingId, request.content(), request.rating()));
    }

    @DeleteMapping("/{findingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a draft that was never submitted")
    void delete(@PathVariable UUID projectId, @PathVariable UUID findingId) {
        findings.delete(projectId, findingId);
    }

    @PostMapping("/{findingId}/status")
    @Operation(summary = "Submit, return (reason), approve (not the author), release (partners) or archive (reason) "
            + "— FND-04")
    FindingResponse changeStatus(@PathVariable UUID projectId, @PathVariable UUID findingId,
            @Valid @RequestBody StatusRequest request) {
        return single(projectId, findings.changeStatus(projectId, findingId, request.status(), request.reason()));
    }

    @PostMapping("/{findingId}/documents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Link a source document with a page or section reference (draft findings) — FND-02")
    void link(@PathVariable UUID projectId, @PathVariable UUID findingId, @Valid @RequestBody LinkRequest request) {
        findings.linkDocument(projectId, findingId, request.documentId(), request.pageReference(), request.note());
    }

    @DeleteMapping("/{findingId}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a source document link (draft findings) — FND-02")
    void unlink(@PathVariable UUID projectId, @PathVariable UUID findingId, @PathVariable UUID documentId) {
        findings.unlinkDocument(projectId, findingId, documentId);
    }

    @PostMapping("/{findingId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Internal review note; never shown to the client — FND-04")
    CommentResponse comment(@PathVariable UUID projectId, @PathVariable UUID findingId,
            @Valid @RequestBody CommentRequest request) {
        var comment = findings.comment(projectId, findingId, request.body());
        return new CommentResponse(comment.getId(), comment.getAuthorId(),
                names(List.of(comment.getAuthorId())).get(comment.getAuthorId()), comment.getBody(),
                comment.getCreatedAt());
    }

    private FindingResponse single(UUID projectId, Finding finding) {
        return response(finding, access.workstreams(projectId), names(people(finding)),
                findings.documentCounts(projectId).getOrDefault(finding.getId(), 0L));
    }

    private DetailResponse detailResponse(UUID projectId, FindingService.FindingDetail detail) {
        var users = new HashSet<>(people(detail.finding()));
        detail.documents().forEach(d -> users.add(d.linkedBy()));
        detail.comments().forEach(c -> users.add(c.getAuthorId()));
        detail.history().forEach(h -> users.add(h.getChangedBy()));
        var names = names(users);
        var finding = response(detail.finding(), access.workstreams(projectId), names,
                detail.documents().size() + detail.hiddenDocuments());
        return new DetailResponse(finding,
                detail.documents().stream().map(d -> new DocumentResponse(d.document().id(), d.document().folderId(),
                        d.document().name(), d.document().contentType(), d.document().scanStatus(), d.pageReference(),
                        d.note(), names.get(d.linkedBy()), d.linkedAt())).toList(),
                detail.hiddenDocuments(),
                detail.comments().stream().map(c -> new CommentResponse(c.getId(), c.getAuthorId(),
                        names.get(c.getAuthorId()), c.getBody(), c.getCreatedAt())).toList(),
                detail.history().stream().map(h -> new HistoryResponse(h.getFromStatus(), h.getToStatus(),
                        names.get(h.getChangedBy()), h.getChangedAt(), h.getNote())).toList());
    }

    private static List<UUID> people(Finding f) {
        var ids = new ArrayList<UUID>();
        ids.add(f.getCreatedBy());
        ids.add(f.getApprovedBy());
        ids.add(f.getReleasedBy());
        return ids;
    }

    private static FindingResponse response(Finding f, Map<UUID, WorkstreamType> types, Map<UUID, String> names,
            long documentCount) {
        return new FindingResponse(f.getId(), f.getNumber(), f.getWorkstreamId(), types.get(f.getWorkstreamId()),
                f.getTitle(), f.getCategory(), f.getDescription(), f.getImpactDescription(), f.getMonetaryImpact(),
                f.getCurrency(), f.getRecommendation(), f.getImpact(), f.getLikelihood(), f.getRiskScore(),
                f.getRiskLevel(), f.getStatus(), f.getCreatedBy(), names.get(f.getCreatedBy()), f.getCreatedAt(),
                f.getUpdatedAt(), f.getSubmittedAt(), f.getApprovedAt(), name(names, f.getApprovedBy()),
                f.getReleasedAt(), name(names, f.getReleasedBy()), f.getArchiveReason(), documentCount);
    }

    private static String name(Map<UUID, String> names, UUID id) {
        return id == null ? null : names.get(id);
    }

    /** Display names for user ids; null ids are ignored and resolve to null. */
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
