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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.dataroom.DataRoomEvents.FolderChanged;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectCreated;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;

class FolderServiceTest {

    private final FolderRepository folders = mock(FolderRepository.class);
    private final FolderAccessRepository grants = mock(FolderAccessRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DataRoomAccess access = mock(DataRoomAccess.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final FolderService service = new FolderService(folders, grants, documents, access, events);

    private final List<Folder> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(access.manageableProject(PROJECT)).thenReturn(context(ProjectRole.PROJECT_MANAGER));
        when(access.project(PROJECT)).thenReturn(context(ProjectRole.PROJECT_MANAGER));
        when(folders.findRoot(any(), any())).thenReturn(Optional.empty());
        when(folders.findChild(any(), any())).thenReturn(Optional.empty());
        when(folders.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(grants.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void namesAreSanitisedAndPathsSplit() {
        assertThat(FolderService.cleanName("  Kontrak\t2025 ")).isEqualTo("Kontrak2025");
        assertThat(FolderService.cleanName("a/b\\c")).isEqualTo("abc");
        assertThat(FolderService.cleanName("x".repeat(300))).hasSize(200);
        assertThatThrownBy(() -> FolderService.cleanName("..")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> FolderService.cleanName("  ")).isInstanceOf(InvalidRequestException.class);
        assertThat(FolderService.segments("01 Corporate//Akta\\2025/")).containsExactly("01 Corporate", "Akta", "2025");
    }

    @Test
    void createsTemplateFoldersWhenAProjectIsCreated() {
        var root = new Folder(PROJECT, null, "01 Corporate", null);
        when(folders.findRoot(PROJECT, "01 Corporate")).thenReturn(Optional.empty(), Optional.of(root));

        service.onProjectCreated(new ProjectCreated(PROJECT, "DD-1",
                List.of("01 Corporate", "01 Corporate/Akta", "02 Finance"), RequestMetadata.SYSTEM));

        assertThat(saved).extracting(Folder::getName).containsExactly("01 Corporate", "Akta", "02 Finance");
        assertThat(saved.get(1).getParentId()).isEqualTo(root.getId());
    }

    @Test
    void createRenameAndDeleteRespectSiblingsAndEmptiness() {
        var folder = service.create(PROJECT, null, "Legal", null);
        verify(events).publishEvent(any(FolderChanged.class));

        when(access.folder(any(), any())).thenReturn(folder);
        when(folders.findRoot(PROJECT, "Tax")).thenReturn(Optional.of(new Folder(PROJECT, null, "Tax", null)));
        assertThatThrownBy(() -> service.update(PROJECT, folder.getId(), "Tax", null, null))
                .isInstanceOf(ResourceConflictException.class);
        service.update(PROJECT, folder.getId(), "Legal & Corporate", true, null);
        assertThat(folder.getName()).isEqualTo("Legal & Corporate");
        assertThat(folder.isDownloadAllowed()).isTrue();
        assertThat(folder.isPrintAllowed()).isFalse();

        when(documents.existsByFolderId(folder.getId())).thenReturn(true);
        assertThatThrownBy(() -> service.delete(PROJECT, folder.getId())).isInstanceOf(ResourceConflictException.class);
        when(documents.existsByFolderId(folder.getId())).thenReturn(false);
        service.delete(PROJECT, folder.getId());
        verify(folders).delete(folder);

        when(folders.findRoot(PROJECT, "legal")).thenReturn(Optional.of(folder)); // the query ignores case
        assertThatThrownBy(() -> service.create(PROJECT, null, "legal", null))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void replacesGrantsForTargetUsersAndGroups() {
        var folder = new Folder(PROJECT, null, "Inbox", null);
        when(access.folder(any(), any())).thenReturn(folder);
        var user = UUID.randomUUID();

        var result = service.replaceGrants(PROJECT, folder.getId(), List.of(
                new FolderService.GrantCommand(FolderAccess.PrincipalType.ROLE, null, FolderAccess.Permission.UPLOAD),
                new FolderService.GrantCommand(FolderAccess.PrincipalType.USER, user, FolderAccess.Permission.VIEW)));

        verify(grants).deleteByFolderId(folder.getId());
        assertThat(result).extracting(FolderAccess::getPrincipalType)
                .containsExactly(FolderAccess.PrincipalType.ROLE, FolderAccess.PrincipalType.USER);
        assertThat(result.getFirst().getProjectRole()).isEqualTo("TARGET");
        assertThatThrownBy(() -> service.replaceGrants(PROJECT, folder.getId(), List.of(
                new FolderService.GrantCommand(FolderAccess.PrincipalType.GROUP, null, FolderAccess.Permission.VIEW))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void grantsAreHiddenFromTheTargetSide() {
        when(access.project(PROJECT)).thenReturn(context(ProjectRole.TARGET));
        assertThatThrownBy(() -> service.grants(PROJECT, UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);

        when(access.project(PROJECT)).thenReturn(context(ProjectRole.ANALYST));
        when(grants.findByFolderId(any())).thenReturn(List.of());
        assertThat(service.grants(PROJECT, UUID.randomUUID())).isEmpty();
        when(access.visibleFolders(any())).thenReturn(List.of());
        assertThat(service.tree(PROJECT)).isEmpty();
    }

    @Test
    void ensurePathReusesExistingFolders() {
        var parent = new Folder(PROJECT, null, "Inbox", null);
        var existing = new Folder(PROJECT, parent.getId(), "2025", null);
        when(folders.findChild(parent.getId(), "2025")).thenReturn(Optional.of(existing));

        var leaf = service.ensurePath(context(ProjectRole.TARGET), parent, List.of("2025", "Q1"));

        assertThat(leaf.getName()).isEqualTo("Q1");
        assertThat(leaf.getParentId()).isEqualTo(existing.getId());
        verify(folders, never()).save(existing);
    }
}
