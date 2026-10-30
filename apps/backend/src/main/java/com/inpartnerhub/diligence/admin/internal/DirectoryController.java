package com.inpartnerhub.diligence.admin.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.admin.Directory.OrganizationSummary;
import com.inpartnerhub.diligence.admin.Directory.UserSummary;
import com.inpartnerhub.diligence.shared.web.ApiPaths;
import com.inpartnerhub.diligence.shared.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Minimal organization and user lookups for project setup (picking client/target companies and team members). */
@RestController
@RequestMapping(ApiPaths.V1 + "/directory")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PARTNER', 'PROJECT_MANAGER')")
@Tag(name = "Directory")
class DirectoryController {

    private final DirectoryService directory;

    DirectoryController(DirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping("/organizations")
    @Operation(summary = "Active organizations, optionally by type (CONSULTANT, CLIENT, TARGET)")
    List<OrganizationSummary> organizations(@RequestParam(required = false) Organization.Type type) {
        return directory.activeOrganizations(type);
    }

    @GetMapping("/users")
    @Operation(summary = "Search active users by name or e-mail, optionally within an organization")
    PageResponse<UserSummary> users(@RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "displayName") Pageable pageable) {
        var page = directory.searchActiveUsers(organizationId, q, pageable);
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
