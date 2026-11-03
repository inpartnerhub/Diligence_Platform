package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}")
@Tag(name = "Data room")
class DocumentClauseController {

    record ClauseResponse(UUID id, UUID projectId, UUID documentId, UUID versionId, String clauseType,
            double confidence, int pageNumber, String extractedText, Instant createdAt) {

        static ClauseResponse of(DocumentClause c) {
            return new ClauseResponse(c.getId(), c.getProjectId(), c.getDocumentId(), c.getVersionId(),
                    c.getClauseType(), c.getConfidence(), c.getPageNumber(), c.getExtractedText(), c.getCreatedAt());
        }
    }

    record ClauseSummaryResponse(UUID id, UUID documentId, String documentName, UUID folderId, String clauseType,
            double confidence, int pageNumber, String extractedText, Instant createdAt) {

        static ClauseSummaryResponse of(DocumentClauseService.ClauseSummary s) {
            return new ClauseSummaryResponse(s.id(), s.documentId(), s.documentName(), s.folderId(), s.clauseType(),
                    s.confidence(), s.pageNumber(), s.extractedText(), s.createdAt());
        }
    }

    private final DocumentClauseService clauseService;

    DocumentClauseController(DocumentClauseService clauseService) {
        this.clauseService = clauseService;
    }

    @GetMapping("/documents/{documentId}/clauses")
    @PreAuthorize("hasAnyRole('PARTNER', 'PROJECT_MANAGER', 'ANALYST', 'OBSERVER')")
    @Operation(summary = "List AI-extracted key contractual clauses for a document (DOC-09)")
    List<ClauseResponse> listByDocument(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        return clauseService.listByDocument(projectId, documentId).stream()
                .map(ClauseResponse::of)
                .toList();
    }

    @GetMapping("/clauses")
    @PreAuthorize("hasAnyRole('PARTNER', 'PROJECT_MANAGER', 'ANALYST', 'OBSERVER')")
    @Operation(summary = "Search and filter key clauses across material contracts within accessible folders (DOC-09)")
    List<ClauseSummaryResponse> listByProject(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q) {
        return clauseService.listByProject(projectId, type, q).stream()
                .map(ClauseSummaryResponse::of)
                .toList();
    }

    @PostMapping("/documents/{documentId}/extract-clauses")
    @PreAuthorize("hasAnyRole('PARTNER', 'PROJECT_MANAGER', 'ANALYST')")
    @Operation(summary = "Trigger key clause extraction for a document's clean version (DOC-09)")
    List<ClauseResponse> extractClauses(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        return clauseService.triggerExtraction(projectId, documentId).stream()
                .map(ClauseResponse::of)
                .toList();
    }
}
