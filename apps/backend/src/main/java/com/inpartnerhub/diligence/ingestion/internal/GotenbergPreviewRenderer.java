package com.inpartnerhub.diligence.ingestion.internal;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.ingestion.ScanException;

/**
 * Gotenberg {@code /forms/libreoffice/convert}: Office, OpenDocument, RTF and text files to PDF. LibreOffice picks the
 * import filter from the file extension, so the extension is derived from the detected type, not the user's name.
 * Disabled gracefully when {@code GOTENBERG_URL} is unset (PDF and image previews still render via PDFBox).
 */
@Component
class GotenbergPreviewRenderer implements PreviewRenderer {

    private static final Logger log = LoggerFactory.getLogger(GotenbergPreviewRenderer.class);
    static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/vnd.ms-powerpoint", "ppt"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("application/vnd.oasis.opendocument.text", "odt"),
            Map.entry("application/vnd.oasis.opendocument.spreadsheet", "ods"),
            Map.entry("application/vnd.oasis.opendocument.presentation", "odp"),
            Map.entry("application/rtf", "rtf"),
            Map.entry("text/plain", "txt"),
            Map.entry("text/csv", "csv"));

    private final RestClient http;

    GotenbergPreviewRenderer(RestClient.Builder builder, IngestionProperties properties) {
        if (properties.gotenbergUrl() == null || properties.gotenbergUrl().toString().isBlank()) {
            this.http = null;
            log.info("GOTENBERG_URL is not set — Office-to-PDF preview conversion disabled (PDF/image previews remain active)");
        } else {
            this.http = IngestionHttp.client(builder, properties.gotenbergUrl(), properties.serviceTimeout());
        }
    }

    @Override
    public boolean supports(String contentType) {
        return http != null && contentType != null && EXTENSIONS.containsKey(IngestionProperties.baseType(contentType));
    }

    @Override
    public void renderPdf(InputStream content, String fileName, String contentType, Path target) {
        if (http == null) {
            throw new ScanException("Gotenberg is not configured (GOTENBERG_URL empty)", false, null);
        }
        var parts = new MultipartBodyBuilder();
        parts.part("files", new InputStreamResource(content))
                .filename(convertibleName(fileName, contentType))
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        try {
            http.post().uri("/forms/libreoffice/convert")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts.build())
                    .exchange((request, response) -> {
                        var status = response.getStatusCode().value();
                        if (status != 200) {
                            // 503: Gotenberg busy or restarting. 400/422: LibreOffice could not open this file.
                            throw new ScanException("Gotenberg could not convert the file (HTTP " + status + ")",
                                    status >= 502 && status <= 504, null);
                        }
                        try (var pdf = response.getBody()) {
                            Files.copy(pdf, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                        return null;
                    });
        } catch (RestClientException ex) {
            throw new ScanException("Gotenberg unavailable: " + ex.getMessage(), true, ex);
        }
    }

    static String convertibleName(String fileName, String contentType) {
        var extension = EXTENSIONS.get(IngestionProperties.baseType(contentType));
        var base = fileName == null || fileName.isBlank() ? "document" : fileName;
        var dot = base.lastIndexOf('.');
        if (dot > 0 && base.substring(dot + 1).toLowerCase(Locale.ROOT).equals(extension)) {
            return base;
        }
        return (dot > 0 ? base.substring(0, dot) : base) + "." + extension;
    }

}
