package com.inpartnerhub.diligence.dataroom.internal;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;

/**
 * Automatically extracts standard contractual clauses whenever a clean document has its text extracted (PRD DOC-09).
 */
@Component
class ClauseExtractionListener {

    private static final Logger log = LoggerFactory.getLogger(ClauseExtractionListener.class);

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentStorage storage;
    private final DocumentClauseService clauseService;

    ClauseExtractionListener(DocumentRepository documents, DocumentVersionRepository versions, DocumentStorage storage,
            DocumentClauseService clauseService) {
        this.documents = documents;
        this.versions = versions;
        this.storage = storage;
        this.clauseService = clauseService;
    }

    @ApplicationModuleListener
    void on(SearchIndexRequested event) {
        var document = documents.findById(event.documentId()).orElse(null);
        if (document == null || !event.versionId().equals(document.getCurrentVersionId())) {
            return;
        }
        var version = versions.findById(event.versionId()).orElse(null);
        if (version == null || version.getScanStatus() != ScanStatus.CLEAN) {
            clauseService.deleteByDocumentId(document.getId());
            return;
        }
        if (version.isTextExtracted()) {
            try (var content = storage.get(RenditionKeys.text(version.getProjectId(), version.getId()))) {
                var text = new String(content.readAllBytes(), StandardCharsets.UTF_8);
                clauseService.extractAndSaveClauses(document.getProjectId(), document.getId(), version.getId(), text);
                log.info("Extracted key clauses for document {} version {}", document.getId(), version.getId());
            } catch (Exception ex) {
                log.warn("Could not extract clauses for document {}: {}", document.getId(), ex.getMessage());
            }
        }
    }
}
