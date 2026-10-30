package com.inpartnerhub.diligence.admin.internal;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The signed-in user's own profile. Available to every authenticated user. */
@RestController
@RequestMapping(ApiPaths.V1 + "/me")
@Tag(name = "Identity")
class ProfileController {

    record OrganizationSummary(UUID id, String name, Organization.Type type) {
    }

    record ProfileResponse(UUID id, String email, String displayName, Set<Role> roles, OrganizationSummary organization,
            String locale, boolean mfaEnabled, Instant lastLoginAt) {
    }

    record UpdateProfileRequest(@NotBlank @Pattern(regexp = "id|en") String locale) {
    }

    private final CurrentUserProvider currentUser;
    private final UserAccountRepository users;
    private final OrganizationRepository organizations;

    ProfileController(CurrentUserProvider currentUser, UserAccountRepository users,
            OrganizationRepository organizations) {
        this.currentUser = currentUser;
        this.users = users;
        this.organizations = organizations;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Return the authenticated user's profile, roles and organization")
    ProfileResponse me() {
        var user = currentUser.requireCurrentUser();
        var account = users.findById(user.id());
        var organization = account.map(UserAccount::getOrganizationId)
                .flatMap(organizations::findById)
                .map(o -> new OrganizationSummary(o.getId(), o.getName(), o.getType()))
                .orElse(null);
        return new ProfileResponse(user.id(), user.email(),
                account.map(UserAccount::getDisplayName).orElse(user.displayName()),
                user.roles(), organization,
                account.map(UserAccount::getLocale).orElse("id"),
                account.map(UserAccount::isMfaEnabled).orElse(false),
                account.map(UserAccount::getLastLoginAt).orElse(null));
    }

    @PatchMapping
    @Transactional
    @Operation(summary = "Update the authenticated user's preferences (UI language)")
    ProfileResponse update(@Valid @RequestBody UpdateProfileRequest request) {
        var user = currentUser.requireCurrentUser();
        users.findById(user.id()).ifPresent(account -> account.changeLocale(request.locale()));
        return me();
    }
}
