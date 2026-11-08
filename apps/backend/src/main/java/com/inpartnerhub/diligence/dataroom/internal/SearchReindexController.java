package com.inpartnerhub.diligence.dataroom.internal;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.dataroom.internal.SearchReindexService.Run;
import com.inpartnerhub.diligence.shared.web.ApiPaths;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1 + "/admin/search/reindex")
// Reads every project's documents: platform operation for the Super Admin only (runbooks opensearch-reindex.md and
// backup-restore.md).
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Administration")
class SearchReindexController {

    private final SearchReindexService reindex;

    SearchReindexController(SearchReindexService reindex) {
        this.reindex = reindex;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Rebuild the search index from PostgreSQL in the background (issue #80); 409 while a run is "
            + "going on. Requires a recent sign-in with MFA.")
    Run start() {
        return reindex.start();
    }

    @GetMapping
    @Operation(summary = "Progress of the latest search re-index run")
    Run latest() {
        return reindex.latest().orElseThrow(() -> new ResourceNotFoundException("SearchReindexRun", "latest"));
    }
}
