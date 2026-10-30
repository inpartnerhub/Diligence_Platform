package com.inpartnerhub.diligence.admin.internal;

import java.util.List;
import java.util.Set;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(AdminApiPaths.ADMIN + "/groups")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Administration")
class GroupController {

    record GroupResponse(UUID id, String name, String description, UUID organizationId, Set<UUID> memberIds) {

        static GroupResponse of(UserGroup g) {
            return new GroupResponse(g.getId(), g.getName(), g.getDescription(), g.getOrganizationId(),
                    g.getMemberIds());
        }
    }

    record CreateGroupRequest(@NotBlank @Size(max = 200) String name, @Size(max = 1000) String description,
            UUID organizationId) {
    }

    record ReplaceMembersRequest(@NotNull Set<UUID> userIds) {
    }

    private final GroupService service;

    GroupController(GroupService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List user groups — ADM-01")
    List<GroupResponse> list() {
        return service.list().stream().map(GroupResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user group — ADM-01")
    GroupResponse create(@Valid @RequestBody CreateGroupRequest request) {
        return GroupResponse.of(service.create(request.name(), request.description(), request.organizationId()));
    }

    @PutMapping("/{id}/members")
    @Operation(summary = "Replace the members of a group — ADM-01")
    GroupResponse replaceMembers(@PathVariable UUID id, @Valid @RequestBody ReplaceMembersRequest request) {
        return GroupResponse.of(service.replaceMembers(id, request.userIds()));
    }
}
