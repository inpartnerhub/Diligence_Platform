package com.inpartnerhub.diligence.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.inpartnerhub.diligence.admin.IdentityAdminClient;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

/**
 * Keycloak Admin REST API adapter, authenticated with the client-credentials grant of {@code diligence-admin-api}.
 * Uses plain {@link RestClient} to avoid coupling to a Keycloak SDK version.
 */
@Component
class KeycloakAdminClient implements IdentityAdminClient {

    private static final Set<String> APPLICATION_ROLES = Stream.of(Role.values())
            .map(Role::realmRoleName)
            .collect(Collectors.toUnmodifiableSet());

    private final RestClient http;
    private final IdentityProperties properties;
    private volatile CachedToken token;

    KeycloakAdminClient(RestClient.Builder builder, IdentityProperties properties) {
        this.http = builder.baseUrl(properties.baseUrl().toString()).build();
        this.properties = properties;
    }

    @Override
    public UUID createUser(NewIdentityUser user) {
        var body = Map.of(
                "username", user.email(),
                "email", user.email(),
                "firstName", user.firstName(),
                "lastName", user.lastName(),
                "enabled", true,
                "emailVerified", false,
                "attributes", Map.of(
                        "locale", List.of(user.locale()),
                        "organizationId", List.of(String.valueOf(user.organizationId()))));
        var response = call(() -> http.post().uri(adminPath("/users"))
                .headers(this::authorize)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity());
        var location = response.getHeaders().getLocation();
        if (location == null) {
            throw new UpstreamServiceException("Keycloak did not return the created user's location", null);
        }
        var path = location.getPath();
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    }

    @Override
    public void replaceRole(UUID userId, Role role) {
        var mappingsPath = adminPath("/users/" + userId + "/role-mappings/realm");
        var current = call(() -> http.get().uri(mappingsPath).headers(this::authorize).retrieve()
                .body(RoleRepresentation[].class));
        var obsolete = Stream.of(current == null ? new RoleRepresentation[0] : current)
                .filter(r -> APPLICATION_ROLES.contains(r.name()) && !r.name().equals(role.realmRoleName()))
                .toList();
        if (!obsolete.isEmpty()) {
            call(() -> http.method(org.springframework.http.HttpMethod.DELETE).uri(mappingsPath)
                    .headers(this::authorize).contentType(MediaType.APPLICATION_JSON).body(obsolete)
                    .retrieve().toBodilessEntity());
        }
        var target = call(() -> http.get().uri(adminPath("/roles/" + role.realmRoleName()))
                .headers(this::authorize).retrieve().body(RoleRepresentation.class));
        call(() -> http.post().uri(mappingsPath).headers(this::authorize)
                .contentType(MediaType.APPLICATION_JSON).body(List.of(target))
                .retrieve().toBodilessEntity());
    }

    @Override
    public void sendRequiredActionsEmail(UUID userId, List<String> requiredActions) {
        call(() -> http.put()
                .uri(uri -> uri.path(adminPath("/users/" + userId + "/execute-actions-email"))
                        .queryParam("lifespan", properties.invitationLifespan().toSeconds())
                        .queryParam("client_id", properties.webClientId())
                        .queryParam("redirect_uri", properties.webRedirectUri())
                        .build())
                .headers(this::authorize)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requiredActions)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public void setEnabled(UUID userId, boolean enabled) {
        call(() -> http.put().uri(adminPath("/users/" + userId)).headers(this::authorize)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("enabled", enabled))
                .retrieve().toBodilessEntity());
    }

    @Override
    public void deleteUser(UUID userId) {
        call(() -> http.delete().uri(adminPath("/users/" + userId)).headers(this::authorize)
                .retrieve().toBodilessEntity());
    }

    private String adminPath(String suffix) {
        return "/admin/realms/" + properties.realm() + suffix;
    }

    private void authorize(HttpHeaders headers) {
        headers.setBearerAuth(accessToken());
    }

    private String accessToken() {
        var cached = token;
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(30))) {
            return cached.value();
        }
        synchronized (this) {
            if (token == null || token.expiresAt().isBefore(Instant.now().plusSeconds(30))) {
                var form = new LinkedMultiValueMap<String, String>();
                form.add("grant_type", "client_credentials");
                form.add("client_id", properties.adminClientId());
                form.add("client_secret", properties.adminClientSecret());
                var response = call(() -> http.post()
                        .uri("/realms/" + properties.realm() + "/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(TokenResponse.class));
                if (response == null) {
                    throw new UpstreamServiceException("Empty token response from Keycloak", null);
                }
                token = new CachedToken(response.accessToken(), Instant.now().plusSeconds(response.expiresIn()));
            }
            return token.value();
        }
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException ex) {
            throw new UpstreamServiceException("Keycloak admin API call failed", ex);
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoleRepresentation(String id, String name) {
    }
}
