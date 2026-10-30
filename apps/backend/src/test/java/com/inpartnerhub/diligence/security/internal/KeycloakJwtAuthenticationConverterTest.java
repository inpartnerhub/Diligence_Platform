package com.inpartnerhub.diligence.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter("diligence-api");

    @Test
    void mapsRealmAndClientRolesToAuthorities() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("6f1c2a52-6a2e-4f0e-9b8e-1d2f3a4b5c6d")
                .claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("analyst", "default-roles-diligence")))
                .claim("resource_access", Map.of("diligence-api", Map.of("roles", List.of("document_reader"))))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        var token = converter.convert(jwt);

        assertThat(token.getName()).isEqualTo("6f1c2a52-6a2e-4f0e-9b8e-1d2f3a4b5c6d");
        assertThat(KeycloakJwtAuthenticationConverter.rolesOf(token))
                .containsExactlyInAnyOrder("ROLE_ANALYST", "ROLE_DEFAULT-ROLES-DILIGENCE", "ROLE_DOCUMENT_READER");
    }

    @Test
    void toleratesTokensWithoutRoleClaims() {
        var jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("svc").build();

        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }
}
