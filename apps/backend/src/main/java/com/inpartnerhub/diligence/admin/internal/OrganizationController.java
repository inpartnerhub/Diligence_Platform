package com.inpartnerhub.diligence.admin.internal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.shared.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(AdminApiPaths.ADMIN + "/organizations")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Administration")
class OrganizationController {

    record OrganizationResponse(UUID id, String name, Organization.Type type, Organization.Status status,
            Instant createdAt) {

        static OrganizationResponse of(Organization o) {
            return new OrganizationResponse(o.getId(), o.getName(), o.getType(), o.getStatus(), o.getCreatedAt());
        }
    }

    record CreateOrganizationRequest(@NotBlank @Size(max = 200) String name, @NotNull Organization.Type type) {
    }

    record UpdateOrganizationRequest(@Size(max = 200) String name, Organization.Status status) {
    }

    private final OrganizationService service;

    OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List organizations (consultant, client and target companies) — ADM-01")
    PageResponse<OrganizationResponse> list(@PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return PageResponse.of(service.list(pageable), OrganizationResponse::of);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an organization — ADM-01")
    OrganizationResponse create(@Valid @RequestBody CreateOrganizationRequest request) {
        return OrganizationResponse.of(service.create(request.name(), request.type()));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Rename or (de)activate an organization — ADM-01")
    OrganizationResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateOrganizationRequest request) {
        return OrganizationResponse.of(service.update(id, request.name(), request.status()));
    }
}
