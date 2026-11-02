package com.inpartnerhub.diligence.dataroom.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/**
 * In-viewer annotations linked to findings or internal notes (PRD DOC-08). Only internal team members
 * can view annotations; target users cannot see them. Analysts, managers, and partners can create,
 * update, and delete annotations.
 */
@Service
@Transactional
class DocumentAnnotationService {

    private final DocumentAnnotationRepository annotations;
    private final DocumentRepository documents;
    private final DataRoomAccess access;
    private final CurrentUserProvider currentUser;

    DocumentAnnotationService(DocumentAnnotationRepository annotations, DocumentRepository documents,
            DataRoomAccess access, CurrentUserProvider currentUser) {
        this.annotations = annotations;
        this.documents = documents;
        this.access = access;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    List<DocumentAnnotation> list(UUID projectId, UUID documentId) {
        var context = access.project(projectId);
        if (!context.isInternal()) {
            throw new AccessDeniedException("Annotations are internal working papers and not visible to target users");
        }
        verifyDocumentExists(projectId, documentId);
        return annotations.findByProjectIdAndDocumentIdOrderByPageNumberAscCreatedAtAsc(projectId, documentId);
    }

    @Transactional(readOnly = true)
    List<DocumentAnnotation> listByFinding(UUID projectId, UUID findingId) {
        var context = access.project(projectId);
        if (!context.isInternal()) {
            throw new AccessDeniedException("Annotations are internal working papers and not visible to target users");
        }
        return annotations.findByProjectIdAndFindingIdOrderByCreatedAtAsc(projectId, findingId);
    }

    DocumentAnnotation create(UUID projectId, UUID documentId, int pageNumber, double xPct, double yPct,
            double widthPct, double heightPct, String color, String comment, UUID findingId) {
        var context = access.writableProject(projectId);
        if (!context.isInternal()
                || !context.hasAnyRole(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER, ProjectRole.ANALYST)) {
            throw new AccessDeniedException("Only analysts and managers can create annotations");
        }
        verifyDocumentExists(projectId, documentId);
        validateCoordinates(xPct, yPct, widthPct, heightPct);
        if (pageNumber < 1) {
            throw new InvalidRequestException("pageNumber must be positive");
        }
        var user = currentUser.requireCurrentUser();
        var displayName = user.displayName() != null && !user.displayName().isBlank() ? user.displayName()
                : (user.email() != null ? user.email() : user.username());
        var annotation = new DocumentAnnotation(UUID.randomUUID(), projectId, documentId, pageNumber, xPct, yPct,
                widthPct, heightPct, color, comment, findingId, user.id(), displayName);
        return annotations.save(annotation);
    }

    DocumentAnnotation update(UUID projectId, UUID documentId, UUID annotationId, Double xPct, Double yPct,
            Double widthPct, Double heightPct, String color, String comment, UUID findingId) {
        var context = access.writableProject(projectId);
        if (!context.isInternal()
                || !context.hasAnyRole(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER, ProjectRole.ANALYST)) {
            throw new AccessDeniedException("Only analysts and managers can update annotations");
        }
        var annotation = annotations.findById(annotationId)
                .filter(a -> a.getProjectId().equals(projectId) && a.getDocumentId().equals(documentId))
                .orElseThrow(() -> new ResourceNotFoundException("Annotation", annotationId));
        if (xPct != null || yPct != null || widthPct != null || heightPct != null) {
            validateCoordinates(
                    xPct != null ? xPct : annotation.getxPct(),
                    yPct != null ? yPct : annotation.getyPct(),
                    widthPct != null ? widthPct : annotation.getWidthPct(),
                    heightPct != null ? heightPct : annotation.getHeightPct());
        }
        annotation.update(xPct, yPct, widthPct, heightPct, color, comment, findingId);
        return annotations.save(annotation);
    }

    void delete(UUID projectId, UUID documentId, UUID annotationId) {
        var context = access.writableProject(projectId);
        if (!context.isInternal()
                || !context.hasAnyRole(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER, ProjectRole.ANALYST)) {
            throw new AccessDeniedException("Only analysts and managers can delete annotations");
        }
        var annotation = annotations.findById(annotationId)
                .filter(a -> a.getProjectId().equals(projectId) && a.getDocumentId().equals(documentId))
                .orElseThrow(() -> new ResourceNotFoundException("Annotation", annotationId));
        annotations.delete(annotation);
    }

    private void verifyDocumentExists(UUID projectId, UUID documentId) {
        documents.findById(documentId)
                .filter(d -> d.getProjectId().equals(projectId))
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }

    private static void validateCoordinates(double x, double y, double width, double height) {
        if (x < 0 || x > 100 || y < 0 || y > 100 || width <= 0 || width > 100 || height <= 0 || height > 100
                || x + width > 100.01 || y + height > 100.01) {
            throw new InvalidRequestException("Invalid annotation bounding box coordinates");
        }
    }
}
