package com.inpartnerhub.diligence.security.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;

@Component
class JwtCurrentUserProvider implements CurrentUserProvider {

    private final String mfaMethod;

    JwtCurrentUserProvider(ObjectProvider<StepUpProperties> properties) {
        this.mfaMethod = properties.getIfAvailable(() -> new StepUpProperties(null, null)).mfaMethod();
    }

    @Override
    public Optional<AuthenticatedUser> currentUser() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return Optional.empty();
        }
        return Optional.of(toUser(token));
    }

    @Override
    public Optional<Instant> authenticatedAt() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return Optional.empty();
        }
        return signInWithMfa(token.getToken(), mfaMethod);
    }

    /**
     * The token's {@code auth_time}, but only when its {@code amr} shows that the sign-in used the second factor
     * (#160): a recent password-only sign-in does not satisfy step-up.
     */
    static Optional<Instant> signInWithMfa(Jwt jwt, String mfaMethod) {
        var methods = jwt.getClaimAsStringList("amr");
        if (methods == null || !methods.contains(mfaMethod)) {
            return Optional.empty();
        }
        return Optional.ofNullable(jwt.getClaimAsInstant("auth_time"));
    }

    static AuthenticatedUser toUser(JwtAuthenticationToken token) {
        var jwt = token.getToken();
        var roles = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(Role::fromAuthority)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
        return new AuthenticatedUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                roles);
    }
}
