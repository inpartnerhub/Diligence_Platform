package com.inpartnerhub.diligence.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
    void sendsIndonesianOcrLanguageHeaderAndExtractsText() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();

        var properties = new IngestionProperties(
                URI.create("http://localhost:9998"),
                URI.create("http://localhost:3002"),
                "localhost",
                3310,
                null,
                null);

        var extractor = new TikaTextExtractor(builder, properties);

        String sampleIndonesianText = "Perjanjian Kerjasama ini dibuat dan ditandatangani pada hari ini.";

        server.expect(requestTo("http://localhost:9998/tika"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Tika-OCRLanguage", "eng+ind"))
                .andExpect(header("X-Tika-PDFOcrStrategy", "auto"))
                .andRespond(withSuccess(sampleIndonesianText, MediaType.TEXT_PLAIN));

        var input = new ByteArrayInputStream("fake-scanned-content".getBytes(StandardCharsets.UTF_8));
        var result = extractor.extract(input, "image/jpeg", 500_000);

        assertThat(result).isEqualTo(sampleIndonesianText);
        server.verify();
    }
}
