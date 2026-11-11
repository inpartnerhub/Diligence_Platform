package com.inpartnerhub.diligence.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.inpartnerhub.diligence.admin.IdentityAdminClient.NewIdentityUser;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

/** #126: a Keycloak that accepts the connection and never answers fails the call within the read timeout. */
class KeycloakAdminClientTimeoutTest {

    private final List<Socket> accepted = new ArrayList<>();
    private ServerSocket server;

    @AfterEach
    void close() throws Exception {
        for (var socket : accepted) {
            socket.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void aStalledIdentityProviderFailsWithinTheReadTimeout() throws Exception {
        server = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            try {
                while (!server.isClosed()) {
                    accepted.add(server.accept()); // never answers
                }
            } catch (Exception ignored) {
                // closed by the test
            }
        });
        var properties = new IdentityProperties(URI.create("http://127.0.0.1:" + server.getLocalPort()), "diligence",
                "admin", "secret", "diligence-web", null, null, Duration.ofSeconds(2), Duration.ofMillis(500));
        var client = new KeycloakAdminClient(RestClient.builder(), properties);

        var started = System.nanoTime();
        assertThatThrownBy(() -> client.createUser(new NewIdentityUser("a@x.test", "A", "B", "en",
                UUID.randomUUID()))).isInstanceOf(UpstreamServiceException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void timeoutsDefaultWhenNotConfigured() {
        var properties = new IdentityProperties(null, "diligence", "admin", "secret", "diligence-web", null, null,
                null, null);
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(15));
    }
}
