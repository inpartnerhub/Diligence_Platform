package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}")
@Tag(name = "Data room")
class DocumentAnnotationController {

    record AnnotationResponse(UUID id, UUID projectId, UUID documentId, int pageNumber, double xPct, double yPct,
            double widthPct, double heightPct, String color, String comment, UUID findingId, UUID createdBy,
            String createdByName, Instant createdAt, Instant updatedAt) {

        static AnnotationResponse of(DocumentAnnotation a) {
            return new AnnotationResponse(a.getId(), a.getProjectId(), a.getDocumentId(), a.getPageNumber(),
                    a.getxPct(), a.getyPct(), a.getWidthPct(), a.getHeightPct(), a.getColor(), a.getComment(),
                    a.getFindingId(), a.getCreatedBy(), a.getCreatedByName(), a.getCreatedAt(), a.getUpdatedAt());
        }
    }

    record CreateAnnotationRequest(
            @NotNull @Min(1) Integer pageNumber,
            @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double xPct,
            @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double yPct,
            @NotNull @DecimalMin("0.01") @DecimalMax("100.0") Double widthPct,
            @NotNull @DecimalMin("0.01") @DecimalMax("100.0") Double heightPct,
            @Size(max = 64) String color,
            @Size(max = 4000) String comment,
            UUID findingId) {
    }

    record UpdateAnnotationRequest(
            @DecimalMin("0.0") @DecimalMax("100.0") Double xPct,
            @DecimalMin("0.0") @DecimalMax("100.0") Double yPct,
            @DecimalMin("0.01") @DecimalMax("100.0") Double widthPct,
            @DecimalMin("0.01") @DecimalMax("100.0") Double heightPct,
            @Size(max = 64) String color,
            @Size(max = 4000) String comment,
            UUID findingId) {
    }

    private final DocumentAnnotationService annotations;

    DocumentAnnotationController(DocumentAnnotationService annotations) {
        this.annotations = annotations;
    }

    @GetMapping("/documents/{documentId}/annotations")
    @Operation(summary = "List annotations on a document — DOC-08")
    List<AnnotationResponse> list(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        return annotations.list(projectId, documentId).stream().map(AnnotationResponse::of).toList();
    }

    @GetMapping("/annotations")
    @Operation(summary = "List annotations linked to a finding — DOC-08")
    List<AnnotationResponse> listByFinding(@PathVariable UUID projectId, @RequestParam UUID findingId) {
        return annotations.listByFinding(projectId, findingId).stream().map(AnnotationResponse::of).toList();
    }

    @PostMapping("/documents/{documentId}/annotations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an annotation on a document page — DOC-08")
    AnnotationResponse create(@PathVariable UUID projectId, @PathVariable UUID documentId,
            @Valid @RequestBody CreateAnnotationRequest request) {
        var created = annotations.create(projectId, documentId, request.pageNumber(), request.xPct(), request.yPct(),
                request.widthPct(), request.heightPct(), request.color(), request.comment(), request.findingId());
        return AnnotationResponse.of(created);
    }

    @PutMapping("/documents/{documentId}/annotations/{annotationId}")
    @Operation(summary = "Update an annotation — DOC-08")
    AnnotationResponse update(@PathVariable UUID projectId, @PathVariable UUID documentId,
            @PathVariable UUID annotationId, @Valid @RequestBody UpdateAnnotationRequest request) {
        var updated = annotations.update(projectId, documentId, annotationId, request.xPct(), request.yPct(),
                request.widthPct(), request.heightPct(), request.color(), request.comment(), request.findingId());
        return AnnotationResponse.of(updated);
    }

    @DeleteMapping("/documents/{documentId}/annotations/{annotationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an annotation — DOC-08")
    void delete(@PathVariable UUID projectId, @PathVariable UUID documentId, @PathVariable UUID annotationId) {
        annotations.delete(projectId, documentId, annotationId);
    }
}
