package com.inpartnerhub.diligence.ingestion.internal;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.DocumentUploaded;
import com.inpartnerhub.diligence.dataroom.ScanResults;
import com.inpartnerhub.diligence.dataroom.ScanResults.Outcome;
import com.inpartnerhub.diligence.ingestion.ContentScanner;
import com.inpartnerhub.diligence.ingestion.ContentTypeDetector;
import com.inpartnerhub.diligence.ingestion.ScanException;
import com.inpartnerhub.diligence.shared.messaging.MessagingTopology;

/**
 * Ingestion worker (PRD Bab 8.3): consumes {@link DocumentUploaded} from RabbitMQ, scans the content with ClamAV,
 * detects its real type with Tika and reports CLEAN / INFECTED / REJECTED / FAILED to the data room. Transient
 * outages are retried; when they persist the document is marked FAILED instead of staying pending forever.
 * Unexpected errors propagate to the listener container (retries, then the dead-letter queue).
 */
@Component
class IngestionPipeline {

    static final int MAX_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final DocumentStorage storage;
    private final ContentScanner scanner;
    private final ContentTypeDetector detector;
    private final ScanResults results;
    private final IngestionProperties properties;

    IngestionPipeline(DocumentStorage storage, ContentScanner scanner, ContentTypeDetector detector,
            ScanResults results, IngestionProperties properties) {
        this.storage = storage;
        this.scanner = scanner;
        this.detector = detector;
        this.results = results;
        this.properties = properties;
    }

    @RabbitListener(queues = MessagingTopology.INGESTION_QUEUE)
    void onDocumentUploaded(DocumentUploaded event) {
        ScanException last = null;
        for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                process(event);
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

    void process(DocumentUploaded event) {
        ContentScanner.ScanResult scan;
        try (var content = storage.get(event.objectKey())) {
            scan = scanner.scan(content);
        } catch (IOException ex) {
            throw new ScanException("Could not read the stored object: " + ex.getMessage(), true, ex);
        }
        if (!scan.clean()) {
            results.record(event.versionId(), Outcome.INFECTED, null, scan.signature());
            return;
        }
        String detected;
        try (var content = storage.get(event.objectKey())) {
            detected = detector.detect(content, event.fileName());
        } catch (IOException ex) {
            throw new ScanException("Could not read the stored object: " + ex.getMessage(), true, ex);
        }
        if (properties.isAllowed(detected)) {
            results.record(event.versionId(), Outcome.CLEAN, detected, null);
        } else {
            results.record(event.versionId(), Outcome.REJECTED, detected,
                    "File type " + detected + " is not allowed in the data room");
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
