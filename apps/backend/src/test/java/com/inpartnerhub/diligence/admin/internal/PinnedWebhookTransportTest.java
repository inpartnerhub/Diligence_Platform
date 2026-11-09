package com.inpartnerhub.diligence.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class PinnedWebhookTransportTest {

    private static final byte[] PUBLIC = { 93, (byte) 184, (byte) 215, 14 };
    private static final byte[] LOOPBACK = { 127, 0, 0, 1 };

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            received.add(exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("X-Event")
                    + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.createContext("/moved", exchange -> {
            received.add("/moved");
            exchange.getResponseHeaders().add("Location", "http://hooks.test:" + port() + "/hook");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
    }

    @Test
    void connectsToTheAddressThePolicyResolved() throws Exception {
        // "hooks.test" exists only in the policy's resolver: the request can only arrive if the client used it.
        var policy = new WebhookTargetPolicy(true, host -> new InetAddress[] { InetAddress.getByAddress(host, LOOPBACK) });
        try (var transport = new PinnedWebhookTransport(policy)) {
            int status = transport.post(URI.create("http://hooks.test:" + port() + "/hook"), Map.of("X-Event", "ping"),
                    "{\"a\":1}");

            assertThat(status).isEqualTo(202);
            assertThat(received).containsExactly("/hook ping {\"a\":1}");
        }
    }

    @Test
    void refusesAHostThatRebindsToAnInternalAddressAfterTheCheck() throws Exception {
        var lookups = new AtomicInteger();
        var policy = new WebhookTargetPolicy(false, host -> new InetAddress[] {
                InetAddress.getByAddress(host, lookups.getAndIncrement() == 0 ? PUBLIC : LOOPBACK) });
        var target = policy.validate("https://rebind.test:" + port() + "/hook"); // first answer: public

        try (var transport = new PinnedWebhookTransport(policy)) {
            assertThatThrownBy(() -> transport.post(target, Map.of(), "{}"))
                    .isInstanceOf(WebhookTargetPolicy.BlockedAddressException.class);
        }
        assertThat(lookups).hasValue(2);
        assertThat(received).isEmpty();
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        var policy = new WebhookTargetPolicy(true, host -> new InetAddress[] { InetAddress.getByAddress(host, LOOPBACK) });
        try (var transport = new PinnedWebhookTransport(policy)) {
            int status = transport.post(URI.create("http://hooks.test:" + port() + "/moved"), Map.of(), "{}");

            assertThat(status).isEqualTo(302);
            assertThat(received).containsExactly("/moved");
        }
    }

    private int port() {
        return server.getAddress().getPort();
    }
}
