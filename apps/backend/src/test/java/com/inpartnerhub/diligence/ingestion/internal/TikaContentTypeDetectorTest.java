package com.inpartnerhub.diligence.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.inpartnerhub.diligence.ingestion.ScanException;
import com.sun.net.httpserver.HttpServer;

/** Detection against stand-ins for Tika 4 ({@code /detect}) and Tika 3 ({@code /detect/stream}). */
class TikaContentTypeDetectorTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void usesDetectOnTika4AndProbesOnlyOnce() throws IOException {
        var detector = detectorFor("/detect");

        assertThat(detector.detect(stream("%PDF-1.7"), "a.pdf")).isEqualTo("application/pdf");
        assertThat(detector.detect(stream("%PDF-1.7"), "b.pdf")).isEqualTo("application/pdf");

        assertThat(requests).containsExactly("PUT /detect 0", "PUT /detect 8 a.pdf", "PUT /detect 8 b.pdf");
    }

    @Test
    void fallsBackToDetectStreamOnTika3() throws IOException {
        var detector = detectorFor("/detect/stream");

        assertThat(detector.detect(stream("%PDF-1.7"), "a.pdf")).isEqualTo("application/pdf");

        assertThat(detector.endpoint()).isEqualTo(TikaContentTypeDetector.LEGACY_DETECT);
        assertThat(requests).containsExactly("PUT /detect 0", "PUT /detect/stream 8 a.pdf");
    }

    @Test
    void unreachableTikaIsRetryableAndNotRemembered() {
        var detector = new TikaContentTypeDetector(RestClient.builder(), properties(URI.create("http://127.0.0.1:1")));

        assertThatThrownBy(() -> detector.detect(stream("x"), "a.pdf"))
                .isInstanceOfSatisfying(ScanException.class, ex -> assertThat(ex.isTransientFailure()).isTrue());
    }

    /** A server that answers detection only on {@code path} and 404 elsewhere. */
    private TikaContentTypeDetector detectorFor(String path) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = exchange.getRequestBody().readAllBytes();
            var disposition = exchange.getRequestHeaders().getFirst("Content-Disposition");
            var name = disposition == null ? "" : " " + disposition.replaceAll(".*filename=\"?([^\";]+)\"?.*", "$1");
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body.length
                    + name);
            if (!exchange.getRequestURI().getPath().equals(path)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            var answer = body.length == 0 ? "application/octet-stream" : "application/pdf\n";
            var bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new TikaContentTypeDetector(RestClient.builder(), properties(url));
    }

    private static IngestionProperties properties(URI tikaUrl) {
        return new IngestionProperties(tikaUrl, null, "localhost", 3310, null, null);
    }

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.US_ASCII));
    }
}
