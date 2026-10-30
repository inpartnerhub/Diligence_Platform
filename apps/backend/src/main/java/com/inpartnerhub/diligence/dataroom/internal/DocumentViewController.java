package com.inpartnerhub.diligence.dataroom.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.inpartnerhub.diligence.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}/documents/{documentId}")
@Tag(name = "Data room")
class DocumentViewController {

    record PreviewResponse(UUID documentId, UUID versionId, int versionNumber, String name, PreviewState previewStatus,
            int pageCount, boolean downloadAllowed, boolean printAllowed, boolean pdfAvailable,
            boolean originalAllowed) {
    }

    private final DocumentViewService views;

    DocumentViewController(DocumentViewService views) {
        this.views = views;
    }

    @GetMapping("/preview")
    @Operation(summary = "Opens a version (default: current) in the viewer: preview state, page count and what the "
            + "caller may do with it. Logged as a view — DOC-03, DOC-05")
    PreviewResponse preview(@PathVariable UUID projectId, @PathVariable UUID documentId,
            @RequestParam(required = false) UUID versionId) {
        var viewable = views.open(projectId, documentId, versionId);
        var version = viewable.version();
        return new PreviewResponse(documentId, version.getId(), version.getVersionNumber(),
                viewable.document().getName(), version.getPreviewStatus(), version.getPageCount(),
                viewable.downloadAllowed(), viewable.printAllowed(), viewable.pdfAvailable(),
                viewable.originalAllowed());
    }

    @GetMapping(path = "/versions/{versionId}/pages/{page}", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "One preview page as PNG, watermarked with the viewer's name, e-mail, IP and time — DOC-04")
    ResponseEntity<byte[]> page(@PathVariable UUID projectId, @PathVariable UUID documentId,
            @PathVariable UUID versionId, @PathVariable int page) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(views.page(projectId, documentId, versionId, page));
    }

    @GetMapping("/download")
    @Operation(summary = "Downloads a version if the folder allows it: a watermarked PDF by default, or the original "
            + "file (internal team, or types without a PDF form). Logged — DOC-04, DOC-05")
    ResponseEntity<StreamingResponseBody> download(@PathVariable UUID projectId, @PathVariable UUID documentId,
            @RequestParam(required = false) UUID versionId,
            @RequestParam(required = false) DocumentViewService.Format format) {
        var download = views.download(projectId, documentId, versionId, format);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(download.contentType()))
                .body(out -> download.writer().writeTo(out));
    }
}
