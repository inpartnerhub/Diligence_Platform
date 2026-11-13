package com.inpartnerhub.diligence.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** #152: a role change adds the new realm role before removing the old one, so a failure never leaves no role. */
class KeycloakAdminClientRoleTest {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile boolean failAdd;
    private volatile boolean failLogout;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void addsTheNewRoleBeforeRemovingTheOldOne() {
        var userId = UUID.randomUUID();

        client().replaceRole(userId, Role.PARTNER);

        var mappings = "/admin/realms/diligence/users/" + userId + "/role-mappings/realm";
        assertThat(calls).containsSubsequence("POST " + mappings, "DELETE " + mappings);
    }

    @Test
    void keepsTheOldRoleWhenAddingTheNewOneFails() {
        failAdd = true;
        var userId = UUID.randomUUID();

        assertThatThrownBy(() -> client().replaceRole(userId, Role.PARTNER))
                .isInstanceOf(UpstreamServiceException.class);
        assertThat(calls).noneMatch(call -> call.startsWith("DELETE "));
    }

    @Test
    void disablingAUserEndsItsSessions() {
        // #173: refresh tokens die at once, not when they would expire.
        var userId = UUID.randomUUID();

        client().setEnabled(userId, false);

        assertThat(calls).containsSubsequence("PUT /admin/realms/diligence/users/" + userId,
                "POST /admin/realms/diligence/users/" + userId + "/logout");
    }

    @Test
    void aFailedLogoutDoesNotUndoTheDisable() {
        failLogout = true;
        var userId = UUID.randomUUID();

        client().setEnabled(userId, false); // no exception: the account is disabled, sign-ins and refreshes stop

        assertThat(calls).contains("PUT /admin/realms/diligence/users/" + userId);
    }

    @Test
    void enablingAUserDoesNotLogItOut() {
        var userId = UUID.randomUUID();

        client().setEnabled(userId, true);

        assertThat(calls).noneMatch(call -> call.endsWith("/logout"));
    }

    private KeycloakAdminClient client() {
        var properties = new IdentityProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "diligence", "admin", "secret", "diligence-web", null, null, Duration.ofSeconds(2),
                Duration.ofSeconds(2));
        return new KeycloakAdminClient(RestClient.builder(), properties);
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        var method = exchange.getRequestMethod();
        var path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        String body = null;
        var status = 204;
        if (path.endsWith("/openid-connect/token")) {
            body = "{\"access_token\":\"t\",\"expires_in\":300}";
            status = 200;
        } else if (path.endsWith("/role-mappings/realm") && method.equals("GET")) {
            body = "[{\"id\":\"1\",\"name\":\"analyst\"}]";
            status = 200;
        } else if (path.endsWith("/roles/partner")) {
            body = "{\"id\":\"2\",\"name\":\"partner\"}";
            status = 200;
        } else if (path.endsWith("/role-mappings/realm") && method.equals("POST") && failAdd) {
            status = 500;
        } else if (path.endsWith("/logout") && failLogout) {
            status = 500;
        }
        if (!path.endsWith("/openid-connect/token")) {
            calls.add(method + " " + path);
        }
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
