package com.inpartnerhub.diligence.security;

import java.util.Optional;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

public interface CurrentUserProvider {

    Optional<AuthenticatedUser> currentUser();

    default AuthenticatedUser requireCurrentUser() {
        return currentUser().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No authenticated user"));
    }
}
