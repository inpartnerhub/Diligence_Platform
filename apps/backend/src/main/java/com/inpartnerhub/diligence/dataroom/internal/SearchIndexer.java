package com.inpartnerhub.diligence.dataroom.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;
import com.inpartnerhub.diligence.search.SearchIndex;
import com.inpartnerhub.diligence.search.SearchIndex.IndexedDocument;

/**
 * Keeps the search index in step with each document's current version (PRD DOC-06). Only CLEAN versions are
 * searchable; the indexed text is what the pipeline extracted (incl. OCR). Runs in the system context.
 */
@Component
class SearchIndexer {

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentStorage storage;
    private final SearchIndex index;

    SearchIndexer(DocumentRepository documents, DocumentVersionRepository versions, DocumentStorage storage,
            SearchIndex index) {
        this.documents = documents;
        this.versions = versions;
        this.storage = storage;
        this.index = index;
    }

    @ApplicationModuleListener
    void on(SearchIndexRequested event) {
        var document = documents.findById(event.documentId()).orElse(null);
        if (document == null || !event.versionId().equals(document.getCurrentVersionId())) {
            return; // superseded by a newer version, which brings its own request
        }
        var version = versions.findById(event.versionId()).orElse(null);
        if (version == null || version.getScanStatus() != ScanStatus.CLEAN) {
            index.remove(document.getId());
            return;
        }
        var text = version.isTextExtracted() ? text(version) : "";
        index.index(new IndexedDocument(document.getId(), version.getId(), document.getProjectId(),
                document.getFolderId(), document.getName(), version.getDetectedContentType(), text,
                version.getUploadedAt()));
    }

    private String text(DocumentVersion version) {
        try (var content = storage.get(RenditionKeys.text(version.getProjectId(), version.getId()))) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
