package com.inpartnerhub.diligence.dataroom.internal;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.inpartnerhub.diligence.shared.web.ApiPaths;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}")
@Tag(name = "Data room")
class DocumentController {

    record DocumentResponse(UUID id, UUID folderId, String name, String contentType, long sizeBytes, String sha256,
            ScanStatus scanStatus, UUID currentVersionId, Instant createdAt) {

        static DocumentResponse of(Document d) {
            return new DocumentResponse(d.getId(), d.getFolderId(), d.getName(), d.getContentType(), d.getSizeBytes(),
                    d.getSha256(), d.getScanStatus(), d.getCurrentVersionId(), d.getCreatedAt());
        }
    }

    record VersionResponse(UUID id, int versionNumber, String fileName, String contentType,
            String detectedContentType, long sizeBytes, String sha256, ScanStatus scanStatus, String scanDetail,
            Instant scannedAt, UUID uploadedBy, Instant uploadedAt) {

        static VersionResponse of(DocumentVersion v) {
            return new VersionResponse(v.getId(), v.getVersionNumber(), v.getFileName(), v.getContentType(),
                    v.getDetectedContentType(), v.getSizeBytes(), v.getSha256(), v.getScanStatus(), v.getScanDetail(),
                    v.getScannedAt(), v.getUploadedBy(), v.getUploadedAt());
        }
    }

    record UploadResponse(DocumentResponse document, VersionResponse version, boolean unchanged,
            List<UUID> duplicateOf) {
    }

    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/folders/{folderId}/documents")
    @Operation(summary = "Documents in a folder — DOC-01")
    List<DocumentResponse> list(@PathVariable UUID projectId, @PathVariable UUID folderId) {
        return documents.list(projectId, folderId).stream().map(DocumentResponse::of).toList();
    }

    @PostMapping(path = "/folders/{folderId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload one file; relativePath (e.g. 'Contracts/2025/a.pdf') creates subfolders for folder "
            + "uploads, an existing name adds a version. The file is scanned before it can be served — DOC-02, DOC-07")
    UploadResponse upload(@PathVariable UUID projectId, @PathVariable UUID folderId,
            @RequestPart("file") MultipartFile file, @RequestParam(required = false) String relativePath) {
        if (file.isEmpty()) {
            throw new InvalidRequestException("Empty files cannot be uploaded");
        }
        try (var content = file.getInputStream()) {
            var result = documents.upload(projectId, folderId, new DocumentService.UploadCommand(
                    file.getOriginalFilename(), relativePath, file.getContentType(), file.getSize()), content);
            return new UploadResponse(DocumentResponse.of(result.document()), VersionResponse.of(result.version()),
                    result.unchanged(), result.duplicateOf());
        } catch (IOException ex) {
            throw new InvalidRequestException("The upload could not be read");
        }
    }

    @GetMapping("/documents/{documentId}/versions")
    @Operation(summary = "Version history of a document, newest first — DOC-07")
    List<VersionResponse> versions(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        return documents.versions(projectId, documentId).stream().map(VersionResponse::of).toList();
    }
}
