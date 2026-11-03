package com.inpartnerhub.diligence.dataroom.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

@Service
@Transactional
class DocumentClauseService {

    private static final Logger log = LoggerFactory.getLogger(DocumentClauseService.class);

    record ClauseSummary(UUID id, UUID documentId, String documentName, UUID folderId, String clauseType,
            double confidence, int pageNumber, String extractedText, Instant createdAt) {
    }

    private final DocumentClauseRepository clauses;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentStorage storage;
    private final DataRoomAccess access;
    private final KeyClauseExtractor extractor;

    DocumentClauseService(DocumentClauseRepository clauses, DocumentRepository documents,
            DocumentVersionRepository versions, DocumentStorage storage, DataRoomAccess access,
            KeyClauseExtractor extractor) {
        this.clauses = clauses;
        this.documents = documents;
        this.versions = versions;
        this.storage = storage;
        this.access = access;
        this.extractor = extractor;
    }

    @Transactional(readOnly = true)
    List<DocumentClause> listByDocument(UUID projectId, UUID documentId) {
        var context = access.project(projectId);
        if (!context.isInternal()) {
            throw new AccessDeniedException("Key clause intelligence is internal and not visible to target users");
        }
        var doc = verifyDocumentExists(projectId, documentId);
        access.folder(context, doc.getFolderId());
        return clauses.findByProjectIdAndDocumentIdOrderByPageNumberAsc(projectId, documentId);
    }

    @Transactional(readOnly = true)
    List<ClauseSummary> listByProject(UUID projectId, String clauseType, String q) {
        var context = access.project(projectId);
        if (!context.isInternal()) {
            throw new AccessDeniedException("Key clause intelligence is internal and not visible to target users");
        }
        var visibleFolderIds = access.visibleFolders(context).stream().map(Folder::getId).toList();
        if (visibleFolderIds.isEmpty()) {
            return List.of();
        }

        List<DocumentClause> all;
        if (clauseType != null && !clauseType.isBlank()) {
            all = clauses.findByProjectIdAndClauseTypeOrderByConfidenceDesc(projectId, clauseType.toUpperCase());
        } else {
            all = clauses.findByProjectIdOrderByCreatedAtDesc(projectId);
        }

        var docMap = new HashMap<UUID, Document>();
        documents.findByProjectId(projectId).forEach(d -> docMap.put(d.getId(), d));

        String queryLower = q != null ? q.trim().toLowerCase() : null;

        return all.stream()
                .filter(c -> {
                    var doc = docMap.get(c.getDocumentId());
                    if (doc == null || !visibleFolderIds.contains(doc.getFolderId())) {
                        return false;
                    }
                    if (queryLower != null && !queryLower.isBlank()) {
                        return c.getExtractedText().toLowerCase().contains(queryLower)
                                || doc.getName().toLowerCase().contains(queryLower)
                                || c.getClauseType().toLowerCase().contains(queryLower);
                    }
                    return true;
                })
                .map(c -> {
                    var doc = docMap.get(c.getDocumentId());
                    return new ClauseSummary(c.getId(), c.getDocumentId(), doc.getName(), doc.getFolderId(),
                            c.getClauseType(), c.getConfidence(), c.getPageNumber(), c.getExtractedText(),
                            c.getCreatedAt());
                })
                .toList();
    }

    List<DocumentClause> extractAndSaveClauses(UUID projectId, UUID documentId, UUID versionId, String text) {
        var extracted = extractor.extract(text);
        clauses.deleteByDocumentId(documentId);
        if (extracted.isEmpty()) {
            return List.of();
        }

        var now = Instant.now();
        var entities = extracted.stream().map(e -> new DocumentClause(
                UUID.randomUUID(), projectId, documentId, versionId, e.clauseType(), e.confidence(),
                e.pageNumber(), e.extractedText(), now)).toList();

        return clauses.saveAll(entities);
    }

    List<DocumentClause> triggerExtraction(UUID projectId, UUID documentId) {
        var context = access.writableProject(projectId);
        if (!context.isInternal()) {
            throw new AccessDeniedException("Key clause intelligence is internal and not visible to target users");
        }
        var doc = verifyDocumentExists(projectId, documentId);
        access.folder(context, doc.getFolderId());

        if (doc.getCurrentVersionId() == null) {
            throw new InvalidRequestException("Document has no current version");
        }
        var version = versions.findById(doc.getCurrentVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", doc.getCurrentVersionId()));

        if (version.getScanStatus() != ScanStatus.CLEAN || !version.isTextExtracted()) {
            throw new InvalidRequestException("Document text has not been extracted or document is not clean");
        }

        try (var content = storage.get(RenditionKeys.text(projectId, version.getId()))) {
            var text = new String(content.readAllBytes(), StandardCharsets.UTF_8);
            return extractAndSaveClauses(projectId, documentId, version.getId(), text);
        } catch (IOException ex) {
            log.error("Failed to read text for document {}", documentId, ex);
            throw new UncheckedIOException(ex);
        }
    }

    void deleteByDocumentId(UUID documentId) {
        clauses.deleteByDocumentId(documentId);
    }

    private Document verifyDocumentExists(UUID projectId, UUID documentId) {
        var doc = documents.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        if (!projectId.equals(doc.getProjectId())) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        return doc;
    }
}
