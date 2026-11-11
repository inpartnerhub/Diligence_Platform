package com.inpartnerhub.diligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * Issue #117 on a real Tomcat: request bodies that are not multipart are capped (1 MB by default) before they are
 * parsed, whether they declare a Content-Length or arrive chunked; small bodies and multipart uploads are unaffected.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import(TestContainersConfiguration.class)
class RequestBodyLimitIntegrationTest {

    /** Above the 1 MB limit, below Tomcat's 2 MB swallow size, so the 413 always reaches the client. */
    private static final String BIG_JSON = "{\"name\":\"" + "a".repeat(1_500_000) + "\"}";

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
    void refusesALargeJsonBodyWithContentLength() throws Exception {
        var response = send("/api/v1/projects", "application/json",
                HttpRequest.BodyPublishers.ofString(BIG_JSON));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("urn:diligence:problem:validation")
                .contains("The request body is too large.");
    }

    @Test
    void refusesALargeChunkedJsonBody() throws Exception {
        var bytes = BIG_JSON.getBytes(StandardCharsets.UTF_8);
        // ofInputStream has no known length, so the body is sent chunked without Content-Length.
        var response = send("/api/v1/projects", "application/json",
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("The request body is too large.");
    }

    @Test
    void smallJsonBodiesAndLargeUploadsAreNotAffected() throws Exception {
        var small = send("/api/v1/projects", "application/json", HttpRequest.BodyPublishers.ofString("{}"));
        assertThat(small.statusCode()).isNotEqualTo(413);

        var multipart = "--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n" + "x".repeat(1_500_000) + "\r\n--b--\r\n";
        var upload = send("/api/v1/projects/" + UUID.randomUUID() + "/folders/" + UUID.randomUUID() + "/documents",
                "multipart/form-data; boundary=b", HttpRequest.BodyPublishers.ofString(multipart));
        assertThat(upload.statusCode()).isNotEqualTo(413);
    }

    private HttpResponse<String> send(String path, String contentType, HttpRequest.BodyPublisher body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer t").header("Content-Type", contentType)
                .POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }
}
