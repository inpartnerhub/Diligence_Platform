package com.inpartnerhub.diligence.dataroom.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.dataroom.DataRoomEvents.DocumentDownloaded;
import com.inpartnerhub.diligence.dataroom.DataRoomEvents.DocumentViewed;
import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

/**
 * Viewing and downloading (PRD DOC-03, DOC-04, DOC-05). Only CLEAN versions are served. Previews are page images
 * stamped with the viewer's watermark, so the file itself never reaches a browser that may not download it.
 * Download and print follow the folder policy (denied by default); the project's partners and managers, who set the
 * policy, are not bound by it. Views and downloads are audited.
 */
@Service
@Transactional
class DocumentViewService {

    enum Format {
        /** Watermarked PDF: the original PDF or the rendition of an Office/image file. */
        PDF,
        /** The uploaded file unchanged: internal team only, or when there is no PDF form (e.g. e-mail). */
        ORIGINAL
    }

    record Viewable(Document document, DocumentVersion version, boolean downloadAllowed, boolean printAllowed,
            boolean pdfAvailable, boolean originalAllowed) {
    }

    record Download(String fileName, String contentType, Format format, Writer writer) {
    }

    @FunctionalInterface
    interface Writer {
        void writeTo(OutputStream out) throws IOException;
    }

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DataRoomAccess access;
    private final DocumentStorage storage;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;
    private final DataRoomProperties properties;
    private final Clock clock;

    @Autowired
    DocumentViewService(DocumentRepository documents, DocumentVersionRepository versions, DataRoomAccess access,
            DocumentStorage storage, CurrentUserProvider currentUser, ApplicationEventPublisher events,
            DataRoomProperties properties) {
        this(documents, versions, access, storage, currentUser, events, properties, Clock.systemUTC());
    }

    DocumentViewService(DocumentRepository documents, DocumentVersionRepository versions, DataRoomAccess access,
            DocumentStorage storage, CurrentUserProvider currentUser, ApplicationEventPublisher events,
            DataRoomProperties properties, Clock clock) {
        this.documents = documents;
        this.versions = versions;
        this.access = access;
        this.storage = storage;
        this.currentUser = currentUser;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** Opens a version (default: current) in the viewer; logged as a view. */
    Viewable open(UUID projectId, UUID documentId, UUID versionId) {
        var viewable = viewable(projectId, documentId, versionId);
        events.publishEvent(new DocumentViewed(projectId, documentId, viewable.version().getId(),
                RequestMetadata.capture()));
        return viewable;
    }

    /** One preview page as PNG with the viewer's watermark. */
    @Transactional(readOnly = true)
    byte[] page(UUID projectId, UUID documentId, UUID versionId, int pageNumber) {
        var version = viewable(projectId, documentId, versionId).version();
        if (version.getPreviewStatus() != PreviewState.READY) {
            throw new ResourceConflictException("No preview is available for this version");
        }
        if (pageNumber < 1 || pageNumber > version.getPageCount()) {
            throw new ResourceNotFoundException("Page", pageNumber);
        }
        try (var png = storage.get(RenditionKeys.page(projectId, version.getId(), pageNumber))) {
            return Watermarker.stampPng(png.readAllBytes(), stamp());
        } catch (IOException | RuntimeException ex) {
            throw new UpstreamServiceException("The preview page could not be loaded", ex);
        }
    }

    /** Checks the policy, logs the download and returns how to stream it (outside the transaction). */
    Download download(UUID projectId, UUID documentId, UUID versionId, Format requested) {
        var viewable = viewable(projectId, documentId, versionId);
        if (!viewable.downloadAllowed()) {
            throw new AccessDeniedException("Downloading is not allowed in this folder");
        }
        var format = requested != null ? requested : viewable.pdfAvailable() ? Format.PDF : Format.ORIGINAL;
        if (format == Format.PDF && !viewable.pdfAvailable()) {
            throw new InvalidRequestException("This document has no PDF form; download the original instead");
        }
        if (format == Format.ORIGINAL && !viewable.originalAllowed()) {
            throw new AccessDeniedException("Only the internal team can download the original file");
        }
        var version = viewable.version();
        events.publishEvent(new DocumentDownloaded(projectId, documentId, version.getId(),
                format.name().toLowerCase(Locale.ROOT), RequestMetadata.capture()));
        if (format == Format.ORIGINAL) {
            var key = version.getObjectKey();
            return new Download(version.getFileName(), contentType(version), format, out -> {
                try (var content = storage.get(key)) {
                    content.transferTo(out);
                }
            });
        }
        var key = version.isDetectedPdf() ? version.getObjectKey() : RenditionKeys.pdf(projectId, version.getId());
        var stamp = stamp();
        var printAllowed = viewable.printAllowed();
        return new Download(pdfName(version.getFileName()), "application/pdf", format,
                out -> watermarkedPdf(key, stamp, printAllowed, out));
    }

    private Viewable viewable(UUID projectId, UUID documentId, UUID versionId) {
        var context = access.project(projectId);
        var document = documents.findById(documentId).filter(d -> d.getProjectId().equals(projectId))
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        var folder = access.folder(context, document.getFolderId()); // RLS + analyst workstream scope
        var id = versionId == null ? document.getCurrentVersionId() : versionId;
        var version = versions.findById(id).filter(v -> v.getDocumentId().equals(documentId))
                .orElseThrow(() -> new ResourceNotFoundException("Version", id));
        if (version.getScanStatus() != ScanStatus.CLEAN) {
            throw new ResourceConflictException("This version has not passed the security scan");
        }
        var managers = context.canManage();
        var pdfAvailable = version.getPreviewStatus() == PreviewState.READY
                || version.isDetectedPdf() && version.getPreviewStatus() != PreviewState.FAILED;
        return new Viewable(document, version, managers || folder.isDownloadAllowed(),
                managers || folder.isPrintAllowed(), pdfAvailable, !pdfAvailable || internal(context));
    }

    private static boolean internal(ProjectContext context) {
        return context.hasAnyRole(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER, ProjectRole.ANALYST);
    }

    private Watermarker.Stamp stamp() {
        var user = currentUser.requireCurrentUser();
        var name = user.displayName() == null || user.displayName().isBlank() ? user.username() : user.displayName();
        return new Watermarker.Stamp(name, user.email(), RequestMetadata.capture().ipAddress(), Instant.now(clock),
                properties.watermarkZone());
    }

    private void watermarkedPdf(String key, Watermarker.Stamp stamp, boolean printAllowed, OutputStream out)
            throws IOException {
        var file = Files.createTempFile("download-", ".pdf");
        try {
            try (InputStream content = storage.get(key)) {
                Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
            }
            Watermarker.stampPdf(file, stamp, printAllowed, out);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static String contentType(DocumentVersion version) {
        return version.getDetectedContentType() != null ? version.getDetectedContentType() : version.getContentType();
    }

    static String pdfName(String fileName) {
        var dot = fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : fileName) + ".pdf";
    }
}
