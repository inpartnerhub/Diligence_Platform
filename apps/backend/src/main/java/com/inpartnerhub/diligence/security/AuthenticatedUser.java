package com.inpartnerhub.diligence.security;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The caller of the current request, derived from the validated access token. */
public record AuthenticatedUser(UUID id, String username, String email, String displayName, Set<Role> roles) {

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    /** The most privileged application role (enum order), used when a single global role is required. */
    public Optional<Role> primaryRole() {
        return roles.stream().min(Comparator.naturalOrder());
    }
}
