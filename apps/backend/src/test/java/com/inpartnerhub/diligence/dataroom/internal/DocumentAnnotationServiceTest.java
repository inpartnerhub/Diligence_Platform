package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

class DocumentAnnotationServiceTest {

    private final DocumentAnnotationRepository annotations = mock(DocumentAnnotationRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DataRoomAccess access = mock(DataRoomAccess.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final DocumentAnnotationService service = new DocumentAnnotationService(annotations, documents, access,
            currentUser);

    private final UUID docId = UUID.randomUUID();
    private final Document doc = new Document(PROJECT, UUID.randomUUID(), "contract.pdf", UUID.randomUUID());
    private final AuthenticatedUser analyst = new AuthenticatedUser(UUID.randomUUID(), "analyst", "analyst@test.com",
            "Analyst User", Set.of(Role.ANALYST));

    @BeforeEach
    void setUp() {
        when(access.project(PROJECT)).thenReturn(context(ProjectRole.ANALYST));
        when(access.writableProject(PROJECT)).thenReturn(context(ProjectRole.ANALYST));
        when(currentUser.requireCurrentUser()).thenReturn(analyst);
        when(documents.findById(docId)).thenReturn(Optional.of(doc));
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
        var ann = new DocumentAnnotation(UUID.randomUUID(), PROJECT, docId, 1, 5.0, 5.0, 10.0, 10.0, "yellow", "test",
                null, analyst.id(), analyst.displayName());
        when(annotations.findByProjectIdAndDocumentIdOrderByPageNumberAscCreatedAtAsc(PROJECT, docId))
                .thenReturn(List.of(ann));
        when(annotations.findByProjectIdAndFindingIdOrderByCreatedAtAsc(PROJECT, ann.getId()))
                .thenReturn(List.of(ann));

        var docAnns = service.list(PROJECT, docId);
        assertThat(docAnns).containsExactly(ann);

        var findingAnns = service.listByFinding(PROJECT, ann.getId());
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
}
