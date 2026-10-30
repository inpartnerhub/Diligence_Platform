package com.inpartnerhub.diligence.ingestion.internal;

import java.io.InputStream;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.inpartnerhub.diligence.ingestion.ContentTypeDetector;
import com.inpartnerhub.diligence.ingestion.ScanException;

/** Apache Tika Server {@code /detect/stream}: content-based detection, with the file name as a hint. */
@Component
class TikaContentTypeDetector implements ContentTypeDetector {

    private final RestClient http;

    TikaContentTypeDetector(RestClient.Builder builder, IngestionProperties properties) {
        this.http = IngestionHttp.client(builder, properties.tikaUrl(), properties.serviceTimeout());
    }

    @Override
    public String detect(InputStream content, String fileName) {
        try {
            var detected = http.put().uri("/detect/stream")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("Content-Disposition",
                            ContentDisposition.attachment().filename(fileName).build().toString())
                    .accept(MediaType.TEXT_PLAIN)
                    .body(new InputStreamResource(content))
                    .retrieve()
                    .body(String.class);
            return detected == null ? null : detected.strip();
        } catch (RestClientException ex) {
            throw new ScanException("Tika unavailable: " + ex.getMessage(), true, ex);
        }
    }
}
