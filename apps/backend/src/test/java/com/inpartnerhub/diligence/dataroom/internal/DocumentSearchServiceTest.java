package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.search.SearchIndex;
import com.inpartnerhub.diligence.search.SearchIndex.SearchHit;
import com.inpartnerhub.diligence.search.SearchIndex.SearchPage;
import com.inpartnerhub.diligence.search.SearchIndex.SearchQuery;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

class DocumentSearchServiceTest {

    private final DataRoomAccess access = mock(DataRoomAccess.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final SearchIndex index = mock(SearchIndex.class);
    private final DocumentSearchService service = new DocumentSearchService(access, documents, index);

    @Test
    void searchesOnlyVisibleFoldersAndDropsHitsTheDatabaseDoesNotConfirm() {
        var context = context(ProjectRole.TARGET);
        var granted = new Folder(PROJECT, null, "Shared", null);
        when(access.project(PROJECT)).thenReturn(context);
        when(access.visibleFolders(context)).thenReturn(List.of(granted));
        var visible = cleanDocument(granted.getId(), "Akta.pdf");
        var pending = new Document(PROJECT, granted.getId(), "Draft.pdf", UUID.randomUUID());
        var hidden = UUID.randomUUID(); // filtered by row-level security: not returned by the repository
        when(index.search(any())).thenReturn(new SearchPage(List.of(hit(visible.getId(), granted.getId()),
                hit(pending.getId(), granted.getId()), hit(hidden, UUID.randomUUID())), 3));
        when(documents.findAllById(any())).thenReturn(List.of(visible, pending));

        var page = service.search(PROJECT, "akta", 0, 500);

        assertThat(page.results()).extracting(r -> r.document().getName()).containsExactly("Akta.pdf");
        var query = ArgumentCaptor.forClass(SearchQuery.class);
        verify(index).search(query.capture());
        assertThat(query.getValue().folderIds()).containsExactly(granted.getId());
        assertThat(query.getValue().size()).isEqualTo(DocumentSearchService.MAX_PAGE_SIZE);
    }

    @Test
    void emptyQueriesAndUsersWithoutFoldersNeverReachTheIndex() {
        var context = context(ProjectRole.TARGET);
        when(access.project(PROJECT)).thenReturn(context);
        when(access.visibleFolders(context)).thenReturn(List.of());

        assertThat(service.search(PROJECT, "akta", 0, 20).results()).isEmpty();
        assertThatThrownBy(() -> service.search(PROJECT, " ", 0, 20)).isInstanceOf(InvalidRequestException.class);
        verify(index, never()).search(any());
    }

    private static Document cleanDocument(UUID folderId, String name) {
        var document = new Document(PROJECT, folderId, name, UUID.randomUUID());
        var version = new DocumentVersion(UUID.randomUUID(), document.getId(), PROJECT, 1, "k", name,
                "application/pdf", 1, "h", UUID.randomUUID());
        version.recordScan(ScanStatus.CLEAN, "application/pdf", null, Instant.now());
        document.useVersion(version);
        return document;
    }

    private static SearchHit hit(UUID documentId, UUID folderId) {
        return new SearchHit(documentId, UUID.randomUUID(), folderId, "x", 1, List.of());
    }
}
