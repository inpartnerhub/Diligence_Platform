package com.inpartnerhub.diligence.dataroom.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}/search")
@Tag(name = "Data room")
class DocumentSearchController {

    /** Highlights mark matches with U+E000 … U+E001; clients render them, never as HTML. */
    record SearchResult(UUID documentId, UUID versionId, UUID folderId, String name, String contentType,
            double score, List<String> highlights) {
    }

    record SearchResponse(List<SearchResult> results, long total, int page, int size) {
    }

    private final DocumentSearchService search;

    DocumentSearchController(DocumentSearchService search) {
        this.search = search;
    }

    @GetMapping
    @Operation(summary = "Full-text search (names and content incl. OCR) within the folders the caller can see. "
            + "Supports \"phrases\", -exclusions and OR — DOC-06")
    SearchResponse search(@PathVariable UUID projectId, @RequestParam String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var found = search.search(projectId, q, page, size);
        var results = found.results().stream().map(r -> new SearchResult(r.document().getId(), r.hit().versionId(),
                r.document().getFolderId(), r.document().getName(), r.document().getContentType(), r.hit().score(),
                r.hit().highlights())).toList();
        return new SearchResponse(results, found.total(), page, size);
    }
}
