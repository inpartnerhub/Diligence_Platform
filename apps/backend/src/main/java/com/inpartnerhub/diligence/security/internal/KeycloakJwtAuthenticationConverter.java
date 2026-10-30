package com.inpartnerhub.diligence.security.internal;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Maps Keycloak realm roles ({@code realm_access.roles}) and client roles ({@code resource_access.<client>.roles})
 * to {@code ROLE_*} authorities; see {@link com.inpartnerhub.diligence.security.Role}.
 */
class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String clientId;

    KeycloakJwtAuthenticationConverter(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var authorities = Stream.concat(realmRoles(jwt), clientRoles(jwt))
                .distinct()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
                .toList();
        // Principal name is the immutable subject (Keycloak user id), used for auditing and ownership.
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static Stream<String> realmRoles(Jwt jwt) {
        return roles(jwt.getClaimAsMap("realm_access"));
    }

    private Stream<String> clientRoles(Jwt jwt) {
        var resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess == null || !(resourceAccess.get(clientId) instanceof Map<?, ?> client)) {
            return Stream.empty();
        }
        return roles(client);
    }

    private static Stream<String> roles(Map<?, ?> claim) {
        if (claim == null || !(claim.get("roles") instanceof Collection<?> roles)) {
            return Stream.empty();
        }
        return roles.stream().map(String::valueOf);
    }

    static List<String> rolesOf(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
