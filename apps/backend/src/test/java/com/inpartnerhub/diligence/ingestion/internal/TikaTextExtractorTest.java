package com.inpartnerhub.diligence.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

class TikaTextExtractorTest {

    @Test
    void defaultsOcrLanguagesToEnglishAndIndonesian() {
        var properties = new IngestionProperties(
                URI.create("http://localhost:9998"),
                URI.create("http://localhost:3002"),
                "localhost",
                3310,
                null,
                null);

        assertThat(properties.ocrLanguages()).isEqualTo("eng+ind");
    }

    @Test
    void customOcrLanguagesArePreserved() {
        var properties = new IngestionProperties(
                URI.create("http://localhost:9998"),
                URI.create("http://localhost:3002"),
                "localhost",
                3310,
                null,
                null,
                "ind",
                null,
                null,
                null,
                null);

        assertThat(properties.ocrLanguages()).isEqualTo("ind");
    }

    @Test
    void normalizesCommaAndWhitespaceSeparatedLanguages() {
        var properties = new IngestionProperties(
                URI.create("http://localhost:9998"),
                URI.create("http://localhost:3002"),
                "localhost",
                3310,
                null,
                null,
                "eng, ind",
                null,
                null,
                null,
                null);

        assertThat(properties.ocrLanguages()).isEqualTo("eng+ind");

        assertThat(IngestionProperties.normalizeOcrLanguages("ind; eng")).isEqualTo("ind+eng");
        assertThat(IngestionProperties.normalizeOcrLanguages("ind   eng")).isEqualTo("ind+eng");
        assertThat(IngestionProperties.normalizeOcrLanguages("eng+ind")).isEqualTo("eng+ind");
        assertThat(IngestionProperties.normalizeOcrLanguages("")).isEqualTo("eng+ind");
        assertThat(IngestionProperties.normalizeOcrLanguages(null)).isEqualTo("eng+ind");
    }

    @Test
    void sendsIndonesianOcrLanguageHeaderAndExtractsText() throws IOException {
        var requests = new CopyOnWriteArrayList<String>();
        var headers = new ConcurrentHashMap<String, String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String sampleIndonesianText = "Perjanjian Kerjasama ini dibuat dan ditandatangani pada hari ini.";
        server.createContext("/tika", exchange -> {
            var ocrLang = exchange.getRequestHeaders().getFirst("X-Tika-OCRLanguage");
            var ocrStrat = exchange.getRequestHeaders().getFirst("X-Tika-PDFOcrStrategy");
            if (ocrLang != null) headers.put("X-Tika-OCRLanguage", ocrLang);
            if (ocrStrat != null) headers.put("X-Tika-PDFOcrStrategy", ocrStrat);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            var bytes = sampleIndonesianText.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            var url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var properties = new IngestionProperties(url, URI.create("http://localhost:3002"), "localhost", 3310, null, null);
            var extractor = new TikaTextExtractor(RestClient.builder(), properties);

            var input = new ByteArrayInputStream("fake-scanned-content".getBytes(StandardCharsets.UTF_8));
            var result = extractor.extract(input, "image/jpeg", 500_000);

            assertThat(result).isEqualTo(sampleIndonesianText);
            assertThat(requests).containsExactly("PUT /tika");
            assertThat(headers).containsEntry("X-Tika-OCRLanguage", "eng+ind");
            assertThat(headers).containsEntry("X-Tika-PDFOcrStrategy", "auto");
        } finally {
            server.stop(0);
        }
    }
}
