package com.inpartnerhub.diligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/**
 * The address stamped into watermarks and the audit log (DOC-04, ADM-03) must be the one our proxies saw, not one
 * a caller wrote into X-Forwarded-For. Needs a real Tomcat: the forwarded headers are resolved by its RemoteIpValve.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import({ TestContainersConfiguration.class, ClientAddressIntegrationTest.EchoConfiguration.class })
class ClientAddressIntegrationTest {

    private static final String PATH = "/api/v1/test/client-address";

    private static final UUID CALLER = UUID.randomUUID();

    @LocalServerPort
    int port;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void acceptAnyToken() {
        when(jwtDecoder.decode(anyString())).thenReturn(Jwt.withTokenValue("t").header("alg", "none")
                .subject(CALLER.toString()).claim("preferred_username", "echo")
                .claim("email", "echo@example.com").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build());
    }

    @Test
    void ignoresAddressesTheCallerWroteIntoTheChain() throws Exception {
        // Browser-supplied entry, then the address the BFF reported, then the BFF pod appended by Kong.
        assertThat(clientAddress("6.6.6.6, 203.0.113.7, 10.42.0.12")).isEqualTo("203.0.113.7");
        // A direct API caller cannot pose as someone else either: Kong appends the address it really saw.
        assertThat(clientAddress("6.6.6.6, 198.51.100.4")).isEqualTo("198.51.100.4");
    }

    @Test
    void usesTheConnectionWithoutForwardedHeaders() throws Exception {
        assertThat(clientAddress(null)).isEqualTo("127.0.0.1");
    }

    private String clientAddress(String forwardedFor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .header("Authorization", "Bearer t");
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EchoConfiguration {

        @Bean
        EchoController clientAddressEcho() {
            return new EchoController();
        }
    }

    @RestController
    static class EchoController {

        @GetMapping(PATH)
        String clientAddress() {
            return RequestMetadata.capture().ipAddress();
        }
    }
}
