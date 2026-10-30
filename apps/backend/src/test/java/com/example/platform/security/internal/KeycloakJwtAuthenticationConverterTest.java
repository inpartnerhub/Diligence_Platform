package com.example.platform.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter("platform-backend");

    @Test
    void mapsRealmAndClientRolesToAuthorities() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("platform_user")))
                .claim("resource_access", Map.of("platform-backend", Map.of("roles", List.of("document_reader"))))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        var token = converter.convert(jwt);

        assertThat(token.getName()).isEqualTo("alice");
        assertThat(KeycloakJwtAuthenticationConverter.rolesOf(token))
                .containsExactlyInAnyOrder("ROLE_PLATFORM_USER", "ROLE_DOCUMENT_READER");
    }

    @Test
    void toleratesTokensWithoutRoleClaims() {
        var jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("svc").build();

        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }
}
