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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(AdminApiPaths.ADMIN + "/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Administration")
class UserAdminController {

    record UserResponse(UUID id, String email, String displayName, UUID organizationId, Role role, String locale,
            UserAccount.Status status, boolean mfaEnabled, Instant lastLoginAt, Instant createdAt) {

        static UserResponse of(UserAccount u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.getOrganizationId(),
                    u.getGlobalRole(), u.getLocale(), u.getStatus(), u.isMfaEnabled(), u.getLastLoginAt(),
                    u.getCreatedAt());
        }
    }

    record InviteUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            UUID organizationId,
            @NotNull Role role,
            @NotBlank @Pattern(regexp = "id|en") String locale) {
    }

    record ChangeRoleRequest(@NotNull Role role) {
    }

    private final UserAdministrationService service;

    UserAdminController(UserAdministrationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List users with optional filters — ADM-01")
    PageResponse<UserResponse> list(
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) UserAccount.Status status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "displayName") Pageable pageable) {
        var filter = new UserAdministrationService.UserFilter(organizationId, role, status, q);
        return PageResponse.of(service.list(filter, pageable), UserResponse::of);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a user")
    UserResponse get(@PathVariable UUID id) {
        return UserResponse.of(service.get(id));
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Invite a user by e-mail; Keycloak sends the set-password / MFA / NDA link — ADM-01, ADM-02")
    UserResponse invite(@Valid @RequestBody InviteUserRequest request) {
        return UserResponse.of(service.invite(new UserAdministrationService.InviteCommand(request.email(),
                request.firstName(), request.lastName(), request.organizationId(), request.role(),
                request.locale())));
    }

    @PatchMapping("/{id}/role")
    @Operation(summary = "Change a user's global role")
    UserResponse changeRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request) {
        return UserResponse.of(service.changeRole(id, request.role()));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable a user (blocks login immediately in Keycloak)")
    UserResponse disable(@PathVariable UUID id) {
        return UserResponse.of(service.setEnabled(id, false));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Re-enable a user")
    UserResponse enable(@PathVariable UUID id) {
        return UserResponse.of(service.setEnabled(id, true));
    }
}
