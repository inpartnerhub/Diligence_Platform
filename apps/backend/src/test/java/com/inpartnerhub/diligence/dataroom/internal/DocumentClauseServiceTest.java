package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

class DocumentClauseServiceTest {

    private final DocumentClauseRepository clauses = mock(DocumentClauseRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final DataRoomAccess access = mock(DataRoomAccess.class);
    private final KeyClauseExtractor extractor = new KeyClauseExtractor();
    private final DocumentClauseService service = new DocumentClauseService(clauses, documents, versions, storage,
            access, extractor);

    private final UUID versionId = UUID.randomUUID();
    private final UUID folderId = UUID.randomUUID();
    private final Document doc = new Document(PROJECT, folderId, "vendor-contract.pdf", UUID.randomUUID());
    private final UUID docId = doc.getId();
    private final Folder folder = new Folder(PROJECT, null, "Contracts", null);

    @BeforeEach
    void setUp() {
        var initialVersion = mock(DocumentVersion.class);
        when(initialVersion.getId()).thenReturn(versionId);
        when(initialVersion.getScanStatus()).thenReturn(ScanStatus.CLEAN);
        doc.useVersion(initialVersion);

        when(access.project(PROJECT)).thenReturn(context(ProjectRole.ANALYST));
        when(access.writableProject(PROJECT)).thenReturn(context(ProjectRole.ANALYST));
        when(access.visibleFolders(any())).thenReturn(List.of(folder));
        when(access.folder(any(), any())).thenReturn(folder);
        when(documents.findById(docId)).thenReturn(Optional.of(doc));
        when(documents.findByProjectId(PROJECT)).thenReturn(List.of(doc));
        when(clauses.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void targetUsersCannotViewClauses() {
        when(access.project(PROJECT)).thenReturn(context(ProjectRole.TARGET));
        assertThatThrownBy(() -> service.listByDocument(PROJECT, docId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not visible to target users");
    }

    @Test
    void extractsAndSavesClauses() {
        String text = "Section 2.1 Change of control requires 30 days prior written notice.";
        var result = service.extractAndSaveClauses(PROJECT, docId, versionId, text);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getClauseType()).isEqualTo("CHANGE_OF_CONTROL");
        verify(clauses).deleteByDocumentId(docId);
        verify(clauses).saveAll(any());
    }

    @Test
    void listByProjectFiltersAccessibleFolders() {
        var clause = new DocumentClause(UUID.randomUUID(), PROJECT, docId, versionId, "NON_COMPETE", 0.95, 1,
                "Non-compete clause text", Instant.now());
        when(clauses.findByProjectIdOrderByCreatedAtDesc(PROJECT)).thenReturn(List.of(clause));

        // When folder is not visible
        when(access.visibleFolders(any())).thenReturn(List.of());
        var empty = service.listByProject(PROJECT, null, null);
        assertThat(empty).isEmpty();

        // When folder is visible
        when(access.visibleFolders(any())).thenReturn(List.of(folder));
        // Ensure folder.getId() matches doc.getFolderId()
        var matchingFolder = mock(Folder.class);
        when(matchingFolder.getId()).thenReturn(folderId);
        when(access.visibleFolders(any())).thenReturn(List.of(matchingFolder));

        var results = service.listByProject(PROJECT, null, "compete");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).clauseType()).isEqualTo("NON_COMPETE");
        assertThat(results.get(0).documentName()).isEqualTo("vendor-contract.pdf");
    }

    @Test
    void triggerExtractionFailsWhenNotClean() {
        var version = mock(DocumentVersion.class);
        when(version.getScanStatus()).thenReturn(ScanStatus.PENDING);
        when(version.isTextExtracted()).thenReturn(false);
        when(versions.findById(versionId)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.triggerExtraction(PROJECT, docId))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("text has not been extracted");
    }

    @Test
    void triggerExtractionSucceedsWhenClean() {
        var version = mock(DocumentVersion.class);
        when(version.getId()).thenReturn(versionId);
        when(version.getScanStatus()).thenReturn(ScanStatus.CLEAN);
        when(version.isTextExtracted()).thenReturn(true);
        when(versions.findById(versionId)).thenReturn(Optional.of(version));

        String text = "Section 9.0 Assignment. Neither party shall assign this agreement.";
        when(storage.get(RenditionKeys.text(PROJECT, versionId)))
                .thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));

        var extracted = service.triggerExtraction(PROJECT, docId);
        assertThat(extracted).hasSize(1);
        assertThat(extracted.get(0).getClauseType()).isEqualTo("ASSIGNMENT");
    }
}
