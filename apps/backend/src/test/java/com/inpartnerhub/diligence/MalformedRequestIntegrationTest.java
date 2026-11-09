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
 * without a name ({@code ?=}) when the parameters are read, which used to surface as 500. The same for multipart
 * bodies without a boundary or terminator (ZAP 2.17, #84). Needs a real Tomcat; MockMvc parses requests differently.
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

    @Test
    void answersMalformedMultipartUploadsWith400() throws Exception {
        var project = UUID.randomUUID();
        var folder = UUID.randomUUID();
        var upload = "/api/v1/projects/" + project + "/folders/" + folder + "/documents";
        var cases = new java.util.LinkedHashMap<String, String[]>();
        cases.put("no boundary", new String[] { "multipart/form-data", "file=x" });
        cases.put("unterminated", new String[] { "multipart/form-data; boundary=b",
                "--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.pdf\"\r\n\r\nabc" });
        cases.put("garbage", new String[] { "multipart/form-data; boundary=b", "not multipart at all" });
        cases.put("path not a uuid", new String[] { "multipart/form-data; boundary=b",
                "--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.pdf\"\r\n"
                        + "Content-Type: application/pdf\r\n\r\nabc\r\n--b--\r\n" });
        for (var entry : cases.entrySet()) {
            var path = entry.getKey().equals("path not a uuid")
                    ? "/api/v1/projects/projectId/folders/folderId/documents" : upload;
            var response = post(path, entry.getValue()[0], entry.getValue()[1]);
            assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(400);
            assertThat(response.body()).as(entry.getKey()).doesNotContain("internal-error");
        }
        assertThat(post(upload, "multipart/form-data", "file=x").body())
                .contains("The multipart request body is malformed.");
    }

    private HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer t").header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                .header("Authorization", "Bearer t").build(), HttpResponse.BodyHandlers.ofString());
    }
}
