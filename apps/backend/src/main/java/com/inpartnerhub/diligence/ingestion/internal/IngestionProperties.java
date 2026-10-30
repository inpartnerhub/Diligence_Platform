package com.inpartnerhub.diligence.ingestion.internal;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * @param allowedContentTypes  detected MIME types accepted in the data room; a trailing '*' matches a prefix
 * @param retryBackoff         wait between attempts when ClamAV, Tika or Gotenberg is unavailable (3 attempts)
 * @param ocrLanguages         Tesseract languages for image-based content, e.g. {@code ind+eng}
 * @param maxTextChars         extracted text kept for search per version
 * @param previewMaxPages      pages rendered for preview; longer documents are previewed up to this page
 * @param previewDpi           resolution of preview page images
 * @param serviceTimeout       read timeout for Tika and Gotenberg (OCR and Office conversion are slow)
 */
@ConfigurationProperties("diligence.ingestion")
record IngestionProperties(URI tikaUrl, URI gotenbergUrl, String clamavHost, int clamavPort,
        List<String> allowedContentTypes, Duration retryBackoff, String ocrLanguages, Integer maxTextChars,
        Integer previewMaxPages, Integer previewDpi, Duration serviceTimeout) {

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

    @ConstructorBinding
    IngestionProperties {
        allowedContentTypes = allowedContentTypes == null || allowedContentTypes.isEmpty() ? DEFAULT_ALLOWED
                : List.copyOf(allowedContentTypes);
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(5) : retryBackoff;
        ocrLanguages = ocrLanguages == null || ocrLanguages.isBlank() ? "eng" : ocrLanguages;
        maxTextChars = maxTextChars == null ? 500_000 : maxTextChars;
        previewMaxPages = previewMaxPages == null ? 300 : previewMaxPages;
        previewDpi = previewDpi == null ? 120 : previewDpi;
        serviceTimeout = serviceTimeout == null ? Duration.ofMinutes(5) : serviceTimeout;
    }

    /** Defaults for everything but the service endpoints (tests). */
    IngestionProperties(URI tikaUrl, URI gotenbergUrl, String clamavHost, int clamavPort,
            List<String> allowedContentTypes, Duration retryBackoff) {
        this(tikaUrl, gotenbergUrl, clamavHost, clamavPort, allowedContentTypes, retryBackoff, null, null, null,
                null, null);
    }

    boolean isAllowed(String contentType) {
        if (contentType == null) {
            return false;
        }
        var base = baseType(contentType);
        return allowedContentTypes.stream().anyMatch(allowed -> allowed.endsWith("*")
                ? base.startsWith(allowed.substring(0, allowed.length() - 1)) : base.equals(allowed));
    }

    static String baseType(String contentType) {
        return contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
    }
}
