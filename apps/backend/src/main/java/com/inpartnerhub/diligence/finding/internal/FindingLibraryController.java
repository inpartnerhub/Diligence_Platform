package com.inpartnerhub.diligence.finding.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
@Tag(name = "Finding template library")
class FindingLibraryController {

    record TemplateResponse(UUID id, WorkstreamType workstreamType, String category, String title,
            String standardDescription, String defaultRecommendation, int defaultImpact, int defaultLikelihood,
            Instant createdAt, Instant updatedAt) {

        static TemplateResponse of(FindingLibraryTemplate t) {
            return new TemplateResponse(t.getId(), t.getWorkstreamType(), t.getCategory(), t.getTitle(),
                    t.getStandardDescription(), t.getDefaultRecommendation(), t.getDefaultImpact(),
                    t.getDefaultLikelihood(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    record TemplateRequest(
            @NotNull WorkstreamType workstreamType,
            @NotBlank @Size(max = Finding.MAX_CATEGORY) String category,
            @NotBlank @Size(max = Finding.MAX_TITLE) String title,
            @NotBlank @Size(max = Finding.MAX_TEXT) String standardDescription,
            @Size(max = Finding.MAX_TEXT) String defaultRecommendation,
            @NotNull @Min(1) @Max(5) Integer defaultImpact,
            @NotNull @Min(1) @Max(5) Integer defaultLikelihood) {
    }

    private final FindingLibraryService libraryService;

    FindingLibraryController(FindingLibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @GetMapping(ApiPaths.V1 + "/finding-library")
    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER', 'PROJECT_MANAGER', 'ANALYST', 'OBSERVER')")
    @Operation(summary = "List standard finding templates from library (FND-06)")
    List<TemplateResponse> list(
            @RequestParam(required = false) WorkstreamType workstream,
            @RequestParam(required = false) String q) {
        return libraryService.list(workstream, q).stream()
                .map(TemplateResponse::of)
                .toList();
    }

    @GetMapping(ApiPaths.V1 + "/finding-library/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER', 'PROJECT_MANAGER', 'ANALYST', 'OBSERVER')")
    @Operation(summary = "Get standard finding template by ID (FND-06)")
    TemplateResponse get(@PathVariable UUID id) {
        return TemplateResponse.of(libraryService.get(id));
    }

    @PostMapping(ApiPaths.V1 + "/admin/finding-library")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create standard finding template in library (FND-06)")
    TemplateResponse create(@Valid @RequestBody TemplateRequest request) {
        return TemplateResponse.of(libraryService.create(
                request.workstreamType(), request.category(), request.title(), request.standardDescription(),
                request.defaultRecommendation(), request.defaultImpact(), request.defaultLikelihood()));
    }

    @PutMapping(ApiPaths.V1 + "/admin/finding-library/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update standard finding template in library (FND-06)")
    TemplateResponse update(@PathVariable UUID id, @Valid @RequestBody TemplateRequest request) {
        return TemplateResponse.of(libraryService.update(
                id, request.workstreamType(), request.category(), request.title(), request.standardDescription(),
                request.defaultRecommendation(), request.defaultImpact(), request.defaultLikelihood()));
    }

    @DeleteMapping(ApiPaths.V1 + "/admin/finding-library/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete standard finding template from library (FND-06)")
    void delete(@PathVariable UUID id) {
        libraryService.delete(id);
    }
}
