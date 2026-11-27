package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.dataroom.AnnotationFindings;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

class DocumentAnnotationServiceTest {

    private final DocumentAnnotationRepository annotations = mock(DocumentAnnotationRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DataRoomAccess access = mock(DataRoomAccess.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final AnnotationFindings findings = mock(AnnotationFindings.class);
    private final DocumentAnnotationService service = new DocumentAnnotationService(annotations, documents, access,
            currentUser, findings);

    private final UUID docId = UUID.randomUUID();
    private final UUID folderId = UUID.randomUUID();
    private final Document doc = mock(Document.class);
    private final Folder folder = mock(Folder.class);
    private final AuthenticatedUser analyst = new AuthenticatedUser(UUID.randomUUID(), "analyst", "analyst@test.com",
            "Analyst User", Set.of(Role.ANALYST));

    @BeforeEach
    void setUp() {
        when(access.project(PROJECT)).thenReturn(context(ProjectRole.ANALYST));
        when(access.writableProject(PROJECT)).thenReturn(context(ProjectRole.ANALYST));
        when(currentUser.requireCurrentUser()).thenReturn(analyst);
        when(doc.getId()).thenReturn(docId);
        when(doc.getProjectId()).thenReturn(PROJECT);
        when(doc.getFolderId()).thenReturn(folderId);
        when(documents.findById(docId)).thenReturn(Optional.of(doc));
        when(documents.findByProjectId(PROJECT)).thenReturn(List.of(doc));
        // The document's folder is within the caller's workstream scope by default.
        when(folder.getId()).thenReturn(folderId);
        when(access.visibleFolders(any())).thenReturn(List.of(folder));
        when(access.folder(any(), any())).thenReturn(folder);
        when(annotations.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void targetUsersCannotViewAnnotations() {
        when(access.project(PROJECT)).thenReturn(context(ProjectRole.TARGET));
        assertThatThrownBy(() -> service.list(PROJECT, docId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("internal working papers");
    }

    @Test
    void targetUsersCannotCreateAnnotations() {
        when(access.writableProject(PROJECT)).thenReturn(context(ProjectRole.TARGET));
        assertThatThrownBy(() -> service.create(PROJECT, docId, 1, 10.0, 20.0, 30.0, 40.0, "yellow", "note", null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only analysts and managers");
    }

    @Test
    void validatesBoundingBoxCoordinates() {
        assertThatThrownBy(() -> service.create(PROJECT, docId, 1, -1.0, 20.0, 30.0, 40.0, null, null, null))
                .isInstanceOf(InvalidRequestException.class);

        assertThatThrownBy(() -> service.create(PROJECT, docId, 1, 10.0, 20.0, 95.0, 40.0, null, null, null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void createsAndSavesAnnotation() {
        var findingId = UUID.randomUUID();
        when(findings.visible(PROJECT, findingId)).thenReturn(true);
        var annotation = service.create(PROJECT, docId, 2, 15.0, 25.0, 30.0, 10.0, "#ffe58f",
                "Indemnity cap mentioned here", findingId);

        assertThat(annotation.getProjectId()).isEqualTo(PROJECT);
        assertThat(annotation.getDocumentId()).isEqualTo(docId);
        assertThat(annotation.getPageNumber()).isEqualTo(2);
        assertThat(annotation.getxPct()).isEqualTo(15.0);
        assertThat(annotation.getyPct()).isEqualTo(25.0);
        assertThat(annotation.getWidthPct()).isEqualTo(30.0);
        assertThat(annotation.getHeightPct()).isEqualTo(10.0);
        assertThat(annotation.getColor()).isEqualTo("#ffe58f");
        assertThat(annotation.getComment()).isEqualTo("Indemnity cap mentioned here");
        assertThat(annotation.getFindingId()).isEqualTo(findingId);
        assertThat(annotation.getCreatedBy()).isEqualTo(analyst.id());
        assertThat(annotation.getCreatedByName()).isEqualTo(analyst.displayName());
        verify(annotations).save(any(DocumentAnnotation.class));
    }

    @Test
    void listsAnnotationsForDocumentAndFinding() {
        var findingId = UUID.randomUUID();
        var ann = new DocumentAnnotation(UUID.randomUUID(), PROJECT, docId, 1, 5.0, 5.0, 10.0, 10.0, "yellow", "test",
                findingId, analyst.id(), analyst.displayName());
        when(annotations.findByProjectIdAndDocumentIdOrderByPageNumberAscCreatedAtAsc(PROJECT, docId))
                .thenReturn(List.of(ann));
        when(annotations.findByProjectIdAndFindingIdOrderByCreatedAtAsc(PROJECT, findingId))
                .thenReturn(List.of(ann));

        var docAnns = service.list(PROJECT, docId);
        assertThat(docAnns).containsExactly(ann);

        var findingAnns = service.listByFinding(PROJECT, findingId);
        assertThat(findingAnns).containsExactly(ann);
    }

    @Test
    void updatesAndDeletesAnnotation() {
        var annId = UUID.randomUUID();
        var ann = new DocumentAnnotation(annId, PROJECT, docId, 1, 5.0, 5.0, 10.0, 10.0, "yellow", "test",
                null, analyst.id(), analyst.displayName());
        when(annotations.findById(annId)).thenReturn(Optional.of(ann));

        var updated = service.update(PROJECT, docId, annId, 10.0, 15.0, 20.0, 25.0, "green", "updated note", null);
        assertThat(updated.getxPct()).isEqualTo(10.0);
        assertThat(updated.getyPct()).isEqualTo(15.0);
        assertThat(updated.getWidthPct()).isEqualTo(20.0);
        assertThat(updated.getHeightPct()).isEqualTo(25.0);
        assertThat(updated.getColor()).isEqualTo("green");
        assertThat(updated.getComment()).isEqualTo("updated note");

        service.delete(PROJECT, docId, annId);
        verify(annotations).delete(ann);
    }

    @Test
    void refusesAFindingThatIsNotInTheProject() {
        var otherProjectsFinding = UUID.randomUUID();
        when(findings.visible(PROJECT, otherProjectsFinding)).thenReturn(false);

        assertThatThrownBy(() -> service.create(PROJECT, docId, 1, 10.0, 10.0, 10.0, 10.0, null, null,
                otherProjectsFinding)).isInstanceOf(InvalidRequestException.class);
        verify(annotations, never()).save(any(DocumentAnnotation.class));
    }

    // Regression (security audit MEDIUM-1, dataroom.DocumentAnnotationService.read-missing-workstream-scope):
    // annotation read/write paths must enforce the document's folder/workstream scope, not just project membership,
    // so an internal analyst restricted to a subset of workstreams cannot reach annotations on documents in
    // workstreams they are not assigned to.

    @Test
    void analystOutsideDocumentWorkstreamCannotListAnnotations() {
        // DataRoomAccess.folder() rejects a folder the caller's workstreams do not cover.
        when(access.folder(any(), eq(folderId))).thenThrow(new ResourceNotFoundException("Folder", folderId));

        assertThatThrownBy(() -> service.list(PROJECT, docId)).isInstanceOf(ResourceNotFoundException.class);
        verify(annotations, never())
                .findByProjectIdAndDocumentIdOrderByPageNumberAscCreatedAtAsc(any(), any());
    }

    @Test
    void listByFindingExcludesAnnotationsOnDocumentsOutsideVisibleFolders() {
        var findingId = UUID.randomUUID();
        var hiddenDocId = UUID.randomUUID();
        var hiddenFolderId = UUID.randomUUID();
        var hiddenDoc = mock(Document.class);
        when(hiddenDoc.getId()).thenReturn(hiddenDocId);
        when(hiddenDoc.getFolderId()).thenReturn(hiddenFolderId);
        when(documents.findByProjectId(PROJECT)).thenReturn(List.of(doc, hiddenDoc));
        // Caller covers only `folder` (folderId); hiddenFolderId is out of scope.
        when(access.visibleFolders(any())).thenReturn(List.of(folder));

        var visibleAnn = new DocumentAnnotation(UUID.randomUUID(), PROJECT, docId, 1, 5.0, 5.0, 10.0, 10.0, "y",
                "visible note", findingId, analyst.id(), analyst.displayName());
        var hiddenAnn = new DocumentAnnotation(UUID.randomUUID(), PROJECT, hiddenDocId, 1, 5.0, 5.0, 10.0, 10.0, "y",
                "out-of-workstream note", findingId, analyst.id(), analyst.displayName());
        when(annotations.findByProjectIdAndFindingIdOrderByCreatedAtAsc(PROJECT, findingId))
                .thenReturn(List.of(visibleAnn, hiddenAnn));

        assertThat(service.listByFinding(PROJECT, findingId)).containsExactly(visibleAnn);
    }

    @Test
    void analystOutsideDocumentWorkstreamCannotModifyAnnotations() {
        var annId = UUID.randomUUID();
        when(access.folder(any(), eq(folderId))).thenThrow(new ResourceNotFoundException("Folder", folderId));

        assertThatThrownBy(() -> service.update(PROJECT, docId, annId, 10.0, 10.0, 10.0, 10.0, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(PROJECT, docId, annId)).isInstanceOf(ResourceNotFoundException.class);
        verify(annotations, never()).save(any());
        verify(annotations, never()).delete(any());
    }
}
