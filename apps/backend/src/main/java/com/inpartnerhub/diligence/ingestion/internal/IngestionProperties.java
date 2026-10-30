package com.inpartnerhub.diligence.ingestion.internal;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowedContentTypes  detected MIME types accepted in the data room; a trailing '*' matches a prefix
 * @param retryBackoff         wait between attempts when ClamAV or Tika is unavailable (3 attempts in total)
 */
@ConfigurationProperties("diligence.ingestion")
record IngestionProperties(URI tikaUrl, URI gotenbergUrl, String clamavHost, int clamavPort,
        List<String> allowedContentTypes, Duration retryBackoff) {

    static final List<String> DEFAULT_ALLOWED = List.of(
            "application/pdf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.*",
            "application/vnd.oasis.opendocument.*",
            "application/rtf",
            "text/plain",
            "text/csv",
            "image/png",
            "image/jpeg",
            "image/tiff",
            "message/rfc822",
            "application/vnd.ms-outlook");

    IngestionProperties {
        allowedContentTypes = allowedContentTypes == null || allowedContentTypes.isEmpty() ? DEFAULT_ALLOWED
                : List.copyOf(allowedContentTypes);
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(5) : retryBackoff;
    }

    boolean isAllowed(String contentType) {
        if (contentType == null) {
            return false;
        }
        var base = contentType.split(";")[0].strip().toLowerCase(java.util.Locale.ROOT);
        return allowedContentTypes.stream().anyMatch(allowed -> allowed.endsWith("*")
                ? base.startsWith(allowed.substring(0, allowed.length() - 1)) : base.equals(allowed));
    }
}
