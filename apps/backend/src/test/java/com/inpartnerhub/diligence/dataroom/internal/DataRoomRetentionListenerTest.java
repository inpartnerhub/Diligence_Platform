package com.inpartnerhub.diligence.dataroom.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

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
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private final DataRoomRetentionListener listener = new DataRoomRetentionListener(
            documents, versions, folders, storage, searchIndex, previewPages, events, transactions);

    private final UUID projectId = UUID.randomUUID();
    private final ProjectDataPurgeRequested request = new ProjectDataPurgeRequested(projectId, "PRJ-TEST",
            RequestMetadata.SYSTEM);

    @Test
    void purgesDataroomInBulkAndPublishesPurgedEvent() {
        when(documents.countByProjectId(projectId)).thenReturn(1_200L);
        when(versions.countByProjectId(projectId)).thenReturn(1_500L);
        when(storage.deletePrefix("projects/" + projectId + "/")).thenReturn(5L);

        listener.on(request);

        // Bulk operations only (#123): one index request, one delete statement per table, no entity loading.
        var order = inOrder(searchIndex, storage, previewPages, documents, folders, events);
        order.verify(searchIndex).removeProject(projectId);
        order.verify(storage).deletePrefix("projects/" + projectId + "/");
        order.verify(previewPages).evictProject(projectId);
        order.verify(documents).deleteAllForProject(projectId);
        order.verify(folders).deleteAllForProject(projectId);
        verify(documents).countByProjectId(projectId);
        verifyNoMoreInteractions(documents, searchIndex);

        var captor = ArgumentCaptor.forClass(ProjectDataPurged.class);
        order.verify(events).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.projectId()).isEqualTo(projectId);
        assertThat(event.code()).isEqualTo("PRJ-TEST");
        assertThat(event.documentsCount()).isEqualTo(1_200L);
        assertThat(event.versionsCount()).isEqualTo(1_500L);
        assertThat(event.s3ObjectsPurged()).isEqualTo(5L);
        assertThat(event.auditAction()).isEqualTo("project.data-purged");
    }

    @Test
    void failedObjectDeletionKeepsTheRowsAndRecordsNoPurge() {
        when(storage.deletePrefix("projects/" + projectId + "/")).thenThrow(new IllegalStateException("S3 down"));

        assertThatThrownBy(() -> listener.on(request)).hasMessage("S3 down");

        verify(documents, never()).deleteAllForProject(projectId);
        verify(folders, never()).deleteAllForProject(projectId);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void failedSearchRemovalKeepsTheRowsAndRecordsNoPurge() {
        doThrow(new UpstreamServiceException("Search is unavailable", null)).when(searchIndex)
                .removeProject(projectId);

        assertThatThrownBy(() -> listener.on(request)).isInstanceOf(UpstreamServiceException.class);

        verify(storage, never()).deletePrefix(any());
        verify(documents, never()).deleteAllForProject(projectId);
        verify(events, never()).publishEvent(any(Object.class));
    }
}
