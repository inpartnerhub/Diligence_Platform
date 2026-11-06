package com.inpartnerhub.diligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Malformed query strings are the caller's error (found by the DAST API scan, #67): Tomcat 11 rejects a parameter
 * without a name ({@code ?=}) when the parameters are read, which used to surface as 500. Needs a real Tomcat;
 * MockMvc parses query strings differently.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import(TestContainersConfiguration.class)
class MalformedRequestIntegrationTest {

    @LocalServerPort
    int port;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void acceptAnyToken() {
        when(jwtDecoder.decode(anyString())).thenReturn(Jwt.withTokenValue("t").header("alg", "none")
                .subject(UUID.randomUUID().toString()).claim("preferred_username", "pm")
                .claim("email", "pm@example.com").claim("realm_access", Map.of("roles", List.of("project_manager")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build());
    }

    @Test
    void answersMalformedQueryStringsWith400() throws Exception {
        for (var query : List.of("/api/v1/projects?=", "/api/v1/projects?=x", "/api/v1/projects?status=ACTIVE&=",
                "/api/v1/directory/organizations?=", "/api/v1/me/notifications?=")) {
            var response = get(query);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(response.body()).as(query).contains("urn:diligence:problem:validation")
                    .contains("The query string is malformed.");
        }
        assertThat(get("/api/v1/projects").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                .header("Authorization", "Bearer t").build(), HttpResponse.BodyHandlers.ofString());
    }
}
