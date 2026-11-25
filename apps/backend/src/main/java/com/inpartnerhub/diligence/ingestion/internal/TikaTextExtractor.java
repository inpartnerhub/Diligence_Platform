package com.inpartnerhub.diligence.ingestion.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.inpartnerhub.diligence.ingestion.ScanException;
import com.inpartnerhub.diligence.ingestion.TextExtractor;

/**
 * Apache Tika Server {@code /tika}: plain text of any supported format. Image-based content (photos, scanned PDF
 * pages) goes through Tesseract OCR inside Tika (DOC-06); the response is read only up to the configured limit.
 * Falls back to {@link EmbeddedTextExtractor} (PDFBox + Apache POI) when {@code TIKA_URL} is unset.
 */
@Component
class TikaTextExtractor implements TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TikaTextExtractor.class);
    private final RestClient http;
    private final String ocrLanguages;

    TikaTextExtractor(RestClient.Builder builder, IngestionProperties properties) {
        if (properties.tikaUrl() == null || properties.tikaUrl().toString().isBlank()) {
            this.http = null;
            log.info("TIKA_URL is not set — using embedded PDFBox/POI text extraction (scanned image OCR disabled)");
        } else {
            this.http = IngestionHttp.client(builder, properties.tikaUrl(), properties.serviceTimeout());
        }
        this.ocrLanguages = properties.ocrLanguages();
    }

    @Override
    public String extract(InputStream content, String contentType, int maxChars) {
        if (http == null) {
            return EmbeddedTextExtractor.extract(content, contentType, maxChars);
        }
        try {
            return http.put().uri("/tika")
                    .contentType(MediaType.parseMediaType(contentType))
                    .accept(MediaType.TEXT_PLAIN)
                    .header("X-Tika-OCRLanguage", ocrLanguages)
                    .header("X-Tika-PDFOcrStrategy", "auto") // OCR only pages without a text layer
                    .body(new InputStreamResource(content))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            // 502–504: Tika restarting or overloaded; anything else is about this file.
                            var status = response.getStatusCode().value();
                            throw new ScanException("Tika could not extract text (HTTP " + status + ")",
                                    status >= 502 && status <= 504, null);
                        }
                        return read(response.getBody(), maxChars);
                    });
        } catch (RestClientException ex) {
            throw new ScanException("Tika unavailable: " + ex.getMessage(), true, ex);
        }
    }

    static String read(InputStream body, int maxChars) throws IOException {
        var text = new StringBuilder();
        var buffer = new char[8192];
        try (var reader = new InputStreamReader(body, StandardCharsets.UTF_8)) {
            int read;
            while (text.length() < maxChars && (read = reader.read(buffer, 0,
                    Math.min(buffer.length, maxChars - text.length()))) != -1) {
                text.append(buffer, 0, read);
            }
        }
        return text.toString().strip();
    }
}
