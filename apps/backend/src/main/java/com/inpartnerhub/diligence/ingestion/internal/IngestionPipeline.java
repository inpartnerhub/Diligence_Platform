package com.inpartnerhub.diligence.ingestion.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.DocumentUploaded;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;
import com.inpartnerhub.diligence.dataroom.ScanResults;
import com.inpartnerhub.diligence.dataroom.ScanResults.Outcome;
import com.inpartnerhub.diligence.dataroom.ScanResults.PreviewStatus;
import com.inpartnerhub.diligence.dataroom.ScanResults.Rendition;
import com.inpartnerhub.diligence.ingestion.ContentScanner;
import com.inpartnerhub.diligence.ingestion.ContentTypeDetector;
import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.ingestion.ScanException;
import com.inpartnerhub.diligence.ingestion.TextExtractor;
import com.inpartnerhub.diligence.shared.messaging.MessagingTopology;

/**
 * Ingestion worker (PRD Bab 8.3): consumes {@link DocumentUploaded} from RabbitMQ, scans the content with ClamAV and
 * detects its real type with Tika. Infected, disallowed or unprocessable versions are reported as such. Clean ones
 * are enriched first — text extraction with OCR (DOC-06), a PDF rendition and pre-rendered page images (DOC-03) — and
 * then reported CLEAN together with what was produced.
 * <p>
 * Transient outages are retried; when they persist the scan is reported FAILED, while a failing enrichment step only
 * degrades the result (no preview or no text) because the document itself is safe. Unexpected errors propagate to
 * the listener container (retries, then the dead-letter queue).
 */
@Component
class IngestionPipeline {

    static final int MAX_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);
    private static final String PDF = "application/pdf";

    private final DocumentStorage storage;
    private final ContentScanner scanner;
    private final ContentTypeDetector detector;
    private final TextExtractor extractor;
    private final PreviewRenderer renderer;
    private final ScanResults results;
    private final IngestionProperties properties;

    IngestionPipeline(DocumentStorage storage, ContentScanner scanner, ContentTypeDetector detector,
            TextExtractor extractor, PreviewRenderer renderer, ScanResults results, IngestionProperties properties) {
        this.storage = storage;
        this.scanner = scanner;
        this.detector = detector;
        this.extractor = extractor;
        this.renderer = renderer;
        this.results = results;
        this.properties = properties;
    }

    @RabbitListener(queues = MessagingTopology.INGESTION_QUEUE)
    void onDocumentUploaded(DocumentUploaded event) {
        ScanException last = null;
        for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                process(event, attempt == MAX_ATTEMPTS);
                return;
            } catch (ScanException ex) {
                last = ex;
                if (!ex.isTransientFailure()) {
                    break;
                }
                log.warn("Ingestion attempt {}/{} for version {} failed: {}", attempt, MAX_ATTEMPTS,
                        event.versionId(), ex.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    pause();
                }
            }
        }
        results.record(event.versionId(), Outcome.FAILED, null, last.getMessage());
    }

    /**
     * @param lastAttempt  on the last attempt a transient enrichment failure degrades the result instead of retrying
     */
    void process(DocumentUploaded event, boolean lastAttempt) {
        ContentScanner.ScanResult scan;
        try (var content = read(event.objectKey())) {
            scan = scanner.scan(content);
        } catch (IOException ex) {
            throw new ScanException("Could not read the stored object: " + ex.getMessage(), true, ex);
        }
        if (!scan.clean()) {
            results.record(event.versionId(), Outcome.INFECTED, null, scan.signature());
            return;
        }
        String detected;
        try (var content = read(event.objectKey())) {
            detected = detector.detect(content, event.fileName());
        } catch (IOException ex) {
            throw new ScanException("Could not read the stored object: " + ex.getMessage(), true, ex);
        }
        if (!properties.isAllowed(detected)) {
            results.record(event.versionId(), Outcome.REJECTED, detected,
                    "File type " + detected + " is not allowed in the data room");
            return;
        }
        var textExtracted = extractText(event, detected, lastAttempt);
        var rendition = render(event, detected, lastAttempt);
        results.recordClean(event.versionId(), detected, new Rendition(rendition.preview(), rendition.pageCount(),
                rendition.pdfRendition(), textExtracted));
    }

    private boolean extractText(DocumentUploaded event, String detected, boolean lastAttempt) {
        String text;
        try (var content = read(event.objectKey())) {
            text = extractor.extract(content, detected, properties.maxTextChars());
        } catch (ScanException ex) {
            if (ex.isTransientFailure() && !lastAttempt) {
                throw ex;
            }
            log.warn("No searchable text for version {}: {}", event.versionId(), ex.getMessage());
            return false;
        } catch (IOException ex) {
            throw new ScanException("Could not read the stored object: " + ex.getMessage(), true, ex);
        }
        if (text.isBlank()) {
            return false;
        }
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        storage.put(RenditionKeys.text(event.projectId(), event.versionId()), new ByteArrayInputStream(bytes),
                bytes.length, "text/plain; charset=utf-8");
        return true;
    }

    private Rendition render(DocumentUploaded event, String detected, boolean lastAttempt) {
        var type = IngestionProperties.baseType(detected);
        var isPdf = PDF.equals(type);
        var isImage = type.startsWith("image/");
        if (!isPdf && !isImage && !renderer.supports(type)) {
            return Rendition.NONE;
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("rendition-");
            var pdf = work.resolve("document.pdf");
            if (isPdf) {
                try (var content = read(event.objectKey())) {
                    Files.copy(content, pdf, StandardCopyOption.REPLACE_EXISTING);
                }
            } else if (isImage) {
                var image = work.resolve("image");
                try (var content = read(event.objectKey())) {
                    Files.copy(content, image);
                }
                PageRasterizer.imagesToPdf(image, pdf);
            } else {
                try (var content = read(event.objectKey())) {
                    renderer.renderPdf(content, event.fileName(), type, pdf);
                }
            }
            var pages = PageRasterizer.render(pdf, properties.previewMaxPages(), properties.previewDpi(),
                    (number, png) -> storage.put(RenditionKeys.page(event.projectId(), event.versionId(), number),
                            new ByteArrayInputStream(png), png.length, "image/png"));
            if (pages == 0) {
                return new Rendition(PreviewStatus.FAILED, 0, false, false);
            }
            if (!isPdf) {
                try (var content = Files.newInputStream(pdf)) {
                    storage.put(RenditionKeys.pdf(event.projectId(), event.versionId()), content, Files.size(pdf),
                            PDF);
                }
            }
            return new Rendition(PreviewStatus.READY, pages, !isPdf, false);
        } catch (ScanException ex) {
            if (ex.isTransientFailure() && !lastAttempt) {
                throw ex;
            }
            log.warn("No preview for version {}: {}", event.versionId(), ex.getMessage());
            return new Rendition(PreviewStatus.FAILED, 0, false, false);
        } catch (IOException | RuntimeException ex) {
            // Corrupt, encrypted or unreadable content: the document stays available for download only.
            log.warn("No preview for version {}: {}", event.versionId(), ex.toString());
            return new Rendition(PreviewStatus.FAILED, 0, false, false);
        } finally {
            delete(work);
        }
    }

    private InputStream read(String objectKey) {
        try {
            return storage.get(objectKey);
        } catch (RuntimeException ex) {
            throw new ScanException("Could not read the stored object: " + ex.getMessage(), true, ex);
        }
    }

    private static void delete(Path directory) {
        if (directory == null) {
            return;
        }
        try (var files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (IOException | UncheckedIOException ex) {
            log.warn("Could not delete temporary rendition files in {}", directory);
        }
    }

    private void pause() {
        try {
            Thread.sleep(properties.retryBackoff());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
