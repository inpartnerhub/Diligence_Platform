package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.inpartnerhub.diligence.dataroom.DataRoomEvents.DocumentVersionAdded;
import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.DocumentUploaded;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

class DocumentServiceTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final FolderService folders = mock(FolderService.class);
    private final DataRoomAccess access = mock(DataRoomAccess.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final DocumentService service = new DocumentService(documents, versions, folders, access, storage,
            currentUser, events);

    private final Folder inbox = new Folder(PROJECT, null, "Inbox", null);

    @BeforeEach
    void setUp() {
        when(access.writableProject(PROJECT)).thenReturn(context(ProjectRole.TARGET));
        when(access.project(PROJECT)).thenReturn(context(ProjectRole.TARGET));
        when(access.uploadableFolder(any(), eq(inbox.getId()))).thenReturn(inbox);
        when(currentUser.requireCurrentUser()).thenReturn(
                new AuthenticatedUser(UUID.randomUUID(), "t", "t@x.test", "T", Set.of(Role.TARGET)));
        when(documents.findByFolderAndName(any(), any())).thenReturn(Optional.empty());
        when(documents.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> ((InputStream) inv.getArgument(1)).readAllBytes()).when(storage)
                .put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void storesFirstVersionWithHashAndStartsTheScan() {
        var result = upload("Akta Pendirian.pdf", null, "hello");

        assertThat(result.unchanged()).isFalse();
        assertThat(result.version().getVersionNumber()).isEqualTo(1);
        assertThat(result.version().getScanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(result.document().getSha256())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(result.version().getObjectKey())
                .startsWith("projects/" + PROJECT + "/documents/" + result.document().getId() + "/");
        verify(events).publishEvent(any(DocumentUploaded.class));
        verify(events).publishEvent(any(DocumentVersionAdded.class));
    }

    @Test
    void sameNameAddsAVersionAndIdenticalContentIsRecognised() {
        var first = upload("Laporan.xlsx", null, "v1");
        when(documents.findByFolderAndName(inbox.getId(), "Laporan.xlsx")).thenReturn(Optional.of(first.document()));
        when(versions.findByDocumentIdOrderByVersionNumberDesc(first.document().getId()))
                .thenReturn(List.of(first.version()));
        when(versions.findById(first.version().getId())).thenReturn(Optional.of(first.version()));

        var unchanged = upload("Laporan.xlsx", null, "v1");
        assertThat(unchanged.unchanged()).isTrue();
        verify(storage).delete(argThatStartsWith("projects/"));

        var second = upload("Laporan.xlsx", null, "v2");
        assertThat(second.version().getVersionNumber()).isEqualTo(2);
        assertThat(first.document().getCurrentVersionId()).isEqualTo(second.version().getId());
    }

    @Test
    void folderUploadsCreateSubfolders() {
        var sub = new Folder(PROJECT, inbox.getId(), "2025", null);
        when(folders.ensurePath(any(), eq(inbox), eq(List.of("Kontrak", "2025")))).thenReturn(sub);

        var result = upload("ignored.pdf", "Kontrak/2025/Perjanjian.pdf", "data");

        assertThat(result.document().getFolderId()).isEqualTo(sub.getId());
        assertThat(result.document().getName()).isEqualTo("Perjanjian.pdf");
    }

    @Test
    void removesStoredObjectWhenMetadataCannotBeSaved() {
        when(versions.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> upload("a.pdf", null, "x")).isInstanceOf(IllegalStateException.class);
        verify(storage).delete(argThatStartsWith("projects/"));
    }

    @Test
    void rejectsEmptyFilesAndHidesForeignDocuments() {
        assertThatThrownBy(() -> service.upload(PROJECT, inbox.getId(),
                new DocumentService.UploadCommand("a.pdf", null, "application/pdf", 0), InputStream.nullInputStream()))
                .isInstanceOf(InvalidRequestException.class);

        var foreign = new Document(UUID.randomUUID(), inbox.getId(), "x.pdf", UUID.randomUUID());
        when(documents.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.versions(PROJECT, foreign.getId()))
                .isInstanceOf(ResourceNotFoundException.class);

        when(access.folder(any(), eq(inbox.getId()))).thenReturn(inbox);
        when(documents.findByFolderIdOrderByName(inbox.getId())).thenReturn(List.of());
        assertThat(service.list(PROJECT, inbox.getId())).isEmpty();
    }

    private DocumentService.UploadResult upload(String name, String relativePath, String content) {
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        return service.upload(PROJECT, inbox.getId(),
                new DocumentService.UploadCommand(name, relativePath, "application/pdf", bytes.length),
                new ByteArrayInputStream(bytes));
    }

    private static String argThatStartsWith(String prefix) {
        return org.mockito.ArgumentMatchers.argThat(key -> key != null && key.startsWith(prefix));
    }
}
