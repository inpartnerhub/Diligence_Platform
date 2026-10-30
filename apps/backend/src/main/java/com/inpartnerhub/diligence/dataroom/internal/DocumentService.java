package com.inpartnerhub.diligence.dataroom.internal;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.dataroom.DataRoomEvents.DocumentVersionAdded;
import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.DocumentUploaded;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

/**
 * Uploads and versions (PRD DOC-02, DOC-07). Content goes to object storage first (status PENDING, never served),
 * then metadata is stored and {@link DocumentUploaded} starts the scan pipeline. Uploading a name that already
 * exists in the folder adds a version; identical content is recognised and not stored twice.
 */
@Service
@Transactional
class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    record UploadCommand(String fileName, String relativePath, String contentType, long sizeBytes) {
    }

    record UploadResult(Document document, DocumentVersion version, boolean unchanged, List<UUID> duplicateOf) {
    }

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final FolderService folders;
    private final DataRoomAccess access;
    private final DocumentStorage storage;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;

    DocumentService(DocumentRepository documents, DocumentVersionRepository versions, FolderService folders,
            DataRoomAccess access, DocumentStorage storage, CurrentUserProvider currentUser,
            ApplicationEventPublisher events) {
        this.documents = documents;
        this.versions = versions;
        this.folders = folders;
        this.access = access;
        this.storage = storage;
        this.currentUser = currentUser;
        this.events = events;
    }

    @Transactional(readOnly = true)
    List<Document> list(UUID projectId, UUID folderId) {
        access.folder(access.project(projectId), folderId);
        return documents.findByFolderIdOrderByName(folderId);
    }

    @Transactional(readOnly = true)
    List<DocumentVersion> versions(UUID projectId, UUID documentId) {
        var document = visibleDocument(projectId, documentId);
        return versions.findByDocumentIdOrderByVersionNumberDesc(document.getId());
    }

    UploadResult upload(UUID projectId, UUID folderId, UploadCommand command, InputStream content) {
        var context = access.writableProject(projectId);
        var uploadFolder = access.uploadableFolder(context, folderId);
        var path = command.relativePath() == null ? List.<String>of() : FolderService.segments(command.relativePath());
        var fileName = FolderService.cleanName(path.isEmpty() ? command.fileName() : path.getLast());
        var folder = path.size() > 1 ? folders.ensurePath(context, uploadFolder, path.subList(0, path.size() - 1))
                : uploadFolder;
        if (command.sizeBytes() <= 0) {
            throw new InvalidRequestException("Empty files cannot be uploaded");
        }

        var userId = currentUser.requireCurrentUser().id();
        var existing = documents.findByFolderAndName(folder.getId(), fileName);
        var document = existing.orElseGet(() -> new Document(projectId, folder.getId(), fileName, userId));
        var versionId = UUID.randomUUID();
        var objectKey = "projects/%s/documents/%s/%s".formatted(projectId, document.getId(), versionId);
        var contentType = command.contentType() == null || command.contentType().isBlank()
                ? "application/octet-stream" : command.contentType();

        var sha256 = store(objectKey, content, command.sizeBytes(), contentType);
        try {
            if (existing.isPresent() && sha256.equals(document.getSha256())
                    && document.getScanStatus() != ScanStatus.INFECTED
                    && document.getScanStatus() != ScanStatus.REJECTED) {
                storage.delete(objectKey);
                var current = versions.findById(document.getCurrentVersionId()).orElseThrow();
                return new UploadResult(document, current, true, List.of());
            }
            var number = existing.isPresent()
                    ? versions.findByDocumentIdOrderByVersionNumberDesc(document.getId()).getFirst().getVersionNumber() + 1
                    : 1;
            var version = new DocumentVersion(versionId, document.getId(), projectId, number, objectKey, fileName,
                    contentType, command.sizeBytes(), sha256, userId);
            document.useVersion(version); // before the first insert: the document mirrors its current version
            if (existing.isEmpty()) {
                documents.saveAndFlush(document);
            }
            version = versions.save(version);
            documents.flush();
            var duplicates = documents.findDuplicates(projectId, sha256, document.getId()).stream()
                    .map(Document::getId).toList();
            events.publishEvent(new DocumentUploaded(projectId, document.getId(), versionId, objectKey, fileName,
                    contentType, command.sizeBytes()));
            events.publishEvent(new DocumentVersionAdded(projectId, document.getId(), versionId, number, fileName,
                    sha256, RequestMetadata.capture()));
            return new UploadResult(document, version, false, duplicates);
        } catch (RuntimeException ex) {
            log.warn("Upload of {} failed after storing {}; removing the object", fileName, objectKey);
            storage.delete(objectKey);
            throw ex;
        }
    }

    private Document visibleDocument(UUID projectId, UUID documentId) {
        var context = access.project(projectId);
        var document = documents.findById(documentId).filter(d -> d.getProjectId().equals(projectId))
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        access.folder(context, document.getFolderId()); // enforces analyst workstream scope
        return document;
    }

    /** Streams the content to storage while computing its SHA-256 (duplicate detection, integrity). */
    private String store(String objectKey, InputStream content, long size, String contentType) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var digesting = new DigestInputStream(content, digest)) {
                storage.put(objectKey, digesting, size, contentType);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        } catch (IOException | RuntimeException ex) {
            throw new UpstreamServiceException("Object storage rejected the upload", ex);
        }
    }
}
