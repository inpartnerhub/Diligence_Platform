package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(ApiPaths.V1 + "/project-templates")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARTNER', 'PROJECT_MANAGER')")
@Tag(name = "Project templates")
class ProjectTemplateController {

    record TemplateResponse(UUID id, String name, String description, TemplateDefinition definition,
            Instant createdAt) {
    }

    record TemplateRequest(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
            @NotNull TemplateDefinition definition) {
    }

    record DuplicateRequest(@NotBlank @Size(max = 200) String name) {
    }

    private final TemplateService templates;

    ProjectTemplateController(TemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    @Operation(summary = "List project templates — PRJ-03")
    List<TemplateResponse> list() {
        return templates.list().stream().map(this::response).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a project template")
    TemplateResponse get(@PathVariable UUID id) {
        return response(templates.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Create a global project template (super admin) — PRJ-03")
    TemplateResponse create(@Valid @RequestBody TemplateRequest request) {
        return response(templates.create(request.name(), request.description(), request.definition()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Update a project template (super admin)")
    TemplateResponse update(@PathVariable UUID id, @Valid @RequestBody TemplateRequest request) {
        return response(templates.update(id, request.name(), request.description(), request.definition()));
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Duplicate a template under a new name (super admin) — PRJ-03")
    TemplateResponse duplicate(@PathVariable UUID id, @Valid @RequestBody DuplicateRequest request) {
        return response(templates.duplicate(id, request.name()));
    }

    private TemplateResponse response(ProjectTemplate t) {
        return new TemplateResponse(t.getId(), t.getName(), t.getDescription(), templates.parse(t.getDefinition()),
                t.getCreatedAt());
    }
}
