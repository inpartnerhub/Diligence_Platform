package com.inpartnerhub.diligence.security;

import java.time.Instant;
import java.util.Optional;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

public interface CurrentUserProvider {

    Optional<AuthenticatedUser> currentUser();

    default AuthenticatedUser requireCurrentUser() {
        return currentUser().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No authenticated user"));
    }

    /**
     * When the caller last actively signed in with MFA, from the token's {@code auth_time}; refreshed tokens keep the
     * original time. Empty when unknown or when the token does not show the second factor ({@code amr}, #160). Used
     * for step-up authentication of sensitive actions.
     */
    default Optional<Instant> authenticatedAt() {
        return Optional.empty();
    }
}
