package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.inpartnerhub.diligence.dataroom.DataRoomEvents.DocumentScanned;
import com.inpartnerhub.diligence.dataroom.ScanResults;
import com.inpartnerhub.diligence.dataroom.ScanResults.Outcome;

class ScanResultServiceTest {

    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Instant now = Instant.parse("2026-11-03T02:00:00Z");
    private final ScanResultService service = new ScanResultService(versions, documents, events,
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void appliesResultToVersionAndCurrentDocument() {
        var document = new Document(PROJECT, UUID.randomUUID(), "a.pdf", UUID.randomUUID());
        var version = new DocumentVersion(UUID.randomUUID(), document.getId(), PROJECT, 1, "k", "a.pdf",
                "application/pdf", 10, "h", UUID.randomUUID());
        document.useVersion(version);
        when(versions.findById(version.getId())).thenReturn(Optional.of(version));
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));

        service.record(version.getId(), Outcome.INFECTED, null, "Eicar-Test-Signature");

        assertThat(version.getScanStatus()).isEqualTo(ScanStatus.INFECTED);
        assertThat(version.getScanDetail()).isEqualTo("Eicar-Test-Signature");
        assertThat(version.getScannedAt()).isEqualTo(now);
        assertThat(document.getScanStatus()).isEqualTo(ScanStatus.INFECTED);
        verify(events).publishEvent(any(DocumentScanned.class));
    }

    @Test
    void cleanResultsStoreTheRenditionAndRefreshSearchForTheCurrentVersion() {
        var document = new Document(PROJECT, UUID.randomUUID(), "a.pdf", UUID.randomUUID());
        var version = new DocumentVersion(UUID.randomUUID(), document.getId(), PROJECT, 1, "k", "a.pdf",
                "application/pdf", 10, "h", UUID.randomUUID());
        document.useVersion(version);
        when(versions.findById(version.getId())).thenReturn(Optional.of(version));
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));

        service.recordClean(version.getId(), "application/pdf",
                new ScanResults.Rendition(ScanResults.PreviewStatus.READY, 4, false, true));

        assertThat(version.getPreviewStatus()).isEqualTo(PreviewState.READY);
        assertThat(version.getPageCount()).isEqualTo(4);
        assertThat(version.isTextExtracted()).isTrue();
        assertThat(document.getScanStatus()).isEqualTo(ScanStatus.CLEAN);
        verify(events).publishEvent(new SearchIndexRequested(document.getId(), version.getId()));

        service.record(version.getId(), Outcome.REJECTED, "application/x-sh", "no");
        assertThat(version.getPreviewStatus()).isEqualTo(PreviewState.UNAVAILABLE);
        assertThat(version.getPageCount()).isZero();
    }

    @Test
    void olderVersionResultsDoNotChangeTheDocumentAndUnknownVersionsAreIgnored() {
        var document = new Document(PROJECT, UUID.randomUUID(), "a.pdf", UUID.randomUUID());
        var old = new DocumentVersion(UUID.randomUUID(), document.getId(), PROJECT, 1, "k1", "a.pdf", "t", 1, "h1",
                UUID.randomUUID());
        var current = new DocumentVersion(UUID.randomUUID(), document.getId(), PROJECT, 2, "k2", "a.pdf", "t", 1,
                "h2", UUID.randomUUID());
        document.useVersion(current);
        when(versions.findById(old.getId())).thenReturn(Optional.of(old));
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));

        service.record(old.getId(), Outcome.CLEAN, "application/pdf", null);
        assertThat(old.getScanStatus()).isEqualTo(ScanStatus.CLEAN);
        assertThat(document.getScanStatus()).isEqualTo(ScanStatus.PENDING);

        var unknown = UUID.randomUUID();
        when(versions.findById(unknown)).thenReturn(Optional.empty());
        service.record(unknown, Outcome.CLEAN, null, "x".repeat(800));
        verify(documents, never()).findById(unknown);
    }
}
