package com.inpartnerhub.diligence.ingestion.internal;

import java.io.InputStream;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.inpartnerhub.diligence.ingestion.ContentTypeDetector;
import com.inpartnerhub.diligence.ingestion.ScanException;

/**
 * Apache Tika Server content-based detection, with the file name as a hint. Tika 4 serves it at {@code PUT /detect},
 * Tika 3 at {@code PUT /detect/stream}; the endpoint is probed once (with an empty body, since an upload stream
 * cannot be replayed) so the pipeline works with either server version during an upgrade.
 */
@Component
class TikaContentTypeDetector implements ContentTypeDetector {

    static final String DETECT = "/detect";
    static final String LEGACY_DETECT = "/detect/stream";

    private final RestClient http;
    private volatile String endpoint;

    TikaContentTypeDetector(RestClient.Builder builder, IngestionProperties properties) {
        this.http = IngestionHttp.client(builder, properties.tikaUrl(), properties.serviceTimeout());
    }

    @Override
    public String detect(InputStream content, String fileName) {
        try {
            var detected = http.put().uri(endpoint())
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

    /** The detection endpoint of the connected server; an unreachable server is retried, not remembered. */
    String endpoint() {
        var resolved = endpoint;
        if (resolved == null) {
            resolved = probe();
            endpoint = resolved;
        }
        return resolved;
    }

    private String probe() {
        try {
            http.put().uri(DETECT)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .accept(MediaType.TEXT_PLAIN)
                    .body(new byte[0])
                    .retrieve()
                    .toBodilessEntity();
            return DETECT;
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)
                    || ex.getStatusCode().isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
                return LEGACY_DETECT;
            }
            throw ex;
        }
    }
}
