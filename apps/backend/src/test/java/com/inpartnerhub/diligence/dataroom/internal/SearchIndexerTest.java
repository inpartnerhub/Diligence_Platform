package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;
import com.inpartnerhub.diligence.dataroom.ScanResults.PreviewStatus;
import com.inpartnerhub.diligence.dataroom.ScanResults.Rendition;
import com.inpartnerhub.diligence.search.SearchIndex;
import com.inpartnerhub.diligence.search.SearchIndex.IndexedDocument;

class SearchIndexerTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final SearchIndex index = mock(SearchIndex.class);
    private final SearchIndexer indexer = new SearchIndexer(documents, versions, storage, index);

    private final Document document = new Document(PROJECT, UUID.randomUUID(), "Akta.pdf", UUID.randomUUID());
    private final DocumentVersion version = new DocumentVersion(UUID.randomUUID(), document.getId(), PROJECT, 1, "k",
            "Akta.pdf", "application/pdf", 1, "h", UUID.randomUUID());

    @Test
    void cleanCurrentVersionsAreIndexedWithTheirExtractedText() {
        version.recordScan(ScanStatus.CLEAN, "application/pdf", null, Instant.now());
        version.recordRendition(new Rendition(PreviewStatus.READY, 1, false, true));
        given();
        when(storage.get(RenditionKeys.text(PROJECT, version.getId())))
                .thenReturn(new ByteArrayInputStream("akta pendirian".getBytes(StandardCharsets.UTF_8)));

        indexer.on(new SearchIndexRequested(document.getId(), version.getId()));

        var indexed = ArgumentCaptor.forClass(IndexedDocument.class);
        verify(index).index(indexed.capture());
        assertThat(indexed.getValue().content()).isEqualTo("akta pendirian");
        assertThat(indexed.getValue().folderId()).isEqualTo(document.getFolderId());
    }

    @Test
    void versionsThatAreNotCleanAreRemovedAndSupersededRequestsIgnored() {
        version.recordScan(ScanStatus.INFECTED, null, "sig", Instant.now());
        given();

        indexer.on(new SearchIndexRequested(document.getId(), version.getId()));
        verify(index).remove(document.getId());

        indexer.on(new SearchIndexRequested(document.getId(), UUID.randomUUID()));
        verify(index, never()).index(any());
        verifyNoInteractions(storage);
    }

    private void given() {
        document.useVersion(version);
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(versions.findById(version.getId())).thenReturn(Optional.of(version));
    }
}
