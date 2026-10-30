package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.dataroom.DataRoomEvents.DocumentScanned;
import com.inpartnerhub.diligence.dataroom.ScanResults;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Applies pipeline results; runs as the trusted "system" context (no user), see RowLevelSecurityTransactionManager. */
@Service
@Transactional
class ScanResultService implements ScanResults {

    private static final Logger log = LoggerFactory.getLogger(ScanResultService.class);

    private final DocumentVersionRepository versions;
    private final DocumentRepository documents;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    ScanResultService(DocumentVersionRepository versions, DocumentRepository documents,
            ApplicationEventPublisher events) {
        this(versions, documents, events, Clock.systemUTC());
    }

    ScanResultService(DocumentVersionRepository versions, DocumentRepository documents,
            ApplicationEventPublisher events, Clock clock) {
        this.versions = versions;
        this.documents = documents;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public void record(UUID versionId, Outcome outcome, String detectedContentType, String detail) {
        var version = versions.findById(versionId).orElse(null);
        if (version == null) {
            log.warn("Scan result for unknown document version {} ignored", versionId);
            return;
        }
        version.recordScan(ScanStatus.valueOf(outcome.name()), detectedContentType, detail, Instant.now(clock));
        documents.findById(version.getDocumentId()).ifPresent(document -> document.syncStatus(version));
        if (outcome == Outcome.INFECTED) {
            log.warn("Malware detected in document version {}: {}", versionId, detail);
        }
        events.publishEvent(new DocumentScanned(version.getProjectId(), version.getDocumentId(), versionId, outcome,
                detail, RequestMetadata.SYSTEM));
    }
}
