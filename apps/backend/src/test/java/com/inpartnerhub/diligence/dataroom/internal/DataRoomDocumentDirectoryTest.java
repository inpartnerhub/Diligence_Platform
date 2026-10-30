package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.project.ProjectRole;

class DataRoomDocumentDirectoryTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DataRoomAccess access = mock(DataRoomAccess.class);
    private final DataRoomDocumentDirectory directory = new DataRoomDocumentDirectory(documents, access);

    @Test
    void returnsOnlyDocumentsInFoldersTheCallerSees() {
        var visibleFolder = new Folder(PROJECT, null, "Inbox", null);
        var hiddenFolder = UUID.randomUUID();
        var visible = new Document(PROJECT, visibleFolder.getId(), "akta.pdf", UUID.randomUUID());
        var hidden = new Document(PROJECT, hiddenFolder, "memo.pdf", UUID.randomUUID());
        var otherProject = new Document(UUID.randomUUID(), visibleFolder.getId(), "x.pdf", UUID.randomUUID());
        var context = context(ProjectRole.TARGET);
        when(access.project(PROJECT)).thenReturn(context);
        when(access.visibleFolders(context)).thenReturn(List.of(visibleFolder));
        when(documents.findAllById(any())).thenReturn(List.of(visible, hidden, otherProject));

        assertThat(directory.findAll(PROJECT, List.of(visible.getId(), hidden.getId(), otherProject.getId())))
                .extracting(d -> d.name()).containsExactly("akta.pdf");
        assertThat(directory.findAll(PROJECT, List.of())).isEmpty();

        when(documents.findAllById(any())).thenReturn(List.of(visible));
        assertThat(directory.find(PROJECT, visible.getId())).isPresent();

        when(access.project(PROJECT)).thenThrow(new AccessDeniedException("client"));
        assertThat(directory.find(PROJECT, visible.getId())).isEmpty();
    }
}
