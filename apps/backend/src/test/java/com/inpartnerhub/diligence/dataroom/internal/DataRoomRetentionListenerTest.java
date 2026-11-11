package com.inpartnerhub.diligence.dataroom.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectDataPurgeRequested;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectDataPurged;
import com.inpartnerhub.diligence.search.SearchIndex;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

class DataRoomRetentionListenerTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final FolderRepository folders = mock(FolderRepository.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final SearchIndex searchIndex = mock(SearchIndex.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PreviewPageCache previewPages = mock(PreviewPageCache.class);

    private final DataRoomRetentionListener listener = new DataRoomRetentionListener(
            documents, versions, folders, storage, searchIndex, previewPages, events);

    @Test
    void purgesDataroomAndPublishesPurgedEvent() {
        var projectId = UUID.randomUUID();
        var docId = UUID.randomUUID();
        var folderId = UUID.randomUUID();
        var doc = new Document(projectId, folderId, "secret.pdf", UUID.randomUUID());
        var version = new DocumentVersion(UUID.randomUUID(), docId, projectId, 1,
                "projects/" + projectId + "/documents/" + docId + "/v1", "secret.pdf",
                "application/pdf", 1024L, "hash", UUID.randomUUID());

        when(documents.findByProjectId(projectId)).thenReturn(List.of(doc));
        when(versions.findByProjectId(projectId)).thenReturn(List.of(version));
        when(storage.deletePrefix("projects/" + projectId + "/")).thenReturn(5L);

        listener.on(new ProjectDataPurgeRequested(projectId, "PRJ-TEST", RequestMetadata.SYSTEM));

        verify(searchIndex).remove(doc.getId());
        verify(storage).deletePrefix("projects/" + projectId + "/");
        verify(previewPages).evictProject(projectId);
        verify(documents).deleteByProjectId(projectId);
        verify(folders).deleteByProjectId(projectId);

        var captor = ArgumentCaptor.forClass(ProjectDataPurged.class);
        verify(events).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.projectId()).isEqualTo(projectId);
        assertThat(event.code()).isEqualTo("PRJ-TEST");
        assertThat(event.documentsCount()).isEqualTo(1L);
        assertThat(event.versionsCount()).isEqualTo(1L);
        assertThat(event.s3ObjectsPurged()).isEqualTo(5L);
        assertThat(event.auditAction()).isEqualTo("project.data-purged");
    }

    @Test
    void failedObjectDeletionKeepsTheRowsAndRecordsNoPurge() {
        var projectId = UUID.randomUUID();
        when(documents.findByProjectId(projectId)).thenReturn(List.of());
        when(storage.deletePrefix("projects/" + projectId + "/")).thenThrow(new IllegalStateException("S3 down"));

        assertThatThrownBy(() -> listener.on(new ProjectDataPurgeRequested(projectId, "PRJ-TEST",
                RequestMetadata.SYSTEM))).hasMessage("S3 down");

        verify(documents, never()).deleteByProjectId(projectId);
        verify(folders, never()).deleteByProjectId(projectId);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void failedSearchRemovalKeepsTheRowsAndRecordsNoPurge() {
        var projectId = UUID.randomUUID();
        var doc = new Document(projectId, UUID.randomUUID(), "secret.pdf", UUID.randomUUID());
        when(documents.findByProjectId(projectId)).thenReturn(List.of(doc));
        doThrow(new UpstreamServiceException("Search is unavailable", null)).when(searchIndex).remove(doc.getId());

        assertThatThrownBy(() -> listener.on(new ProjectDataPurgeRequested(projectId, "PRJ-TEST",
                RequestMetadata.SYSTEM))).isInstanceOf(UpstreamServiceException.class);

        verify(storage, never()).deletePrefix(any());
        verify(documents, never()).deleteByProjectId(projectId);
        verify(events, never()).publishEvent(any(Object.class));
    }
}
