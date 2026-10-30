package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.archived;
import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.context;
import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.scoped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

class DataRoomAccessTest {

    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final FolderRepository folders = mock(FolderRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DataRoomAccess access = new DataRoomAccess(projects, folders, jdbc);

    @Test
    void clientSideHasNoDataRoomAndInvisibleProjectsAreNotFound() {
        when(projects.context(PROJECT)).thenReturn(Optional.of(context(ProjectRole.CLIENT)));
        assertThatThrownBy(() -> access.project(PROJECT)).isInstanceOf(AccessDeniedException.class);

        when(projects.context(PROJECT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> access.project(PROJECT)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void archivedProjectsAreReadOnlyAndOnlyManagersManage() {
        when(projects.context(PROJECT)).thenReturn(Optional.of(archived(ProjectRole.PROJECT_MANAGER)));
        assertThat(access.project(PROJECT)).isNotNull();
        assertThatThrownBy(() -> access.writableProject(PROJECT)).isInstanceOf(ResourceConflictException.class);

        when(projects.context(PROJECT)).thenReturn(Optional.of(context(ProjectRole.ANALYST)));
        assertThatThrownBy(() -> access.manageableProject(PROJECT)).isInstanceOf(AccessDeniedException.class);
        when(projects.context(PROJECT)).thenReturn(Optional.of(context(ProjectRole.PARTNER)));
        assertThat(access.manageableProject(PROJECT).canManage()).isTrue();
    }

    @Test
    void analystsOnlySeeFoldersOfTheirWorkstreamsIncludingInheritedOnes() {
        var legal = UUID.randomUUID();
        var tax = UUID.randomUUID();
        var legalRoot = new Folder(PROJECT, null, "Legal", legal);
        var legalSub = new Folder(PROJECT, legalRoot.getId(), "Contracts", null);
        var taxRoot = new Folder(PROJECT, null, "Tax", tax);
        var general = new Folder(PROJECT, null, "General", null);
        when(folders.findByProjectIdOrderByName(PROJECT)).thenReturn(List.of(legalRoot, legalSub, taxRoot, general));

        assertThat(access.visibleFolders(scoped(ProjectRole.ANALYST, legal)))
                .containsExactly(legalRoot, legalSub, general);
        assertThat(access.visibleFolders(context(ProjectRole.ANALYST))).hasSize(4);
        assertThat(access.visibleFolders(scoped(ProjectRole.PROJECT_MANAGER, legal))).hasSize(4);
        assertThatThrownBy(() -> access.folder(scoped(ProjectRole.ANALYST, legal), taxRoot.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void uploadRightsByRole() {
        var folder = new Folder(PROJECT, null, "Inbox", null);
        when(folders.findByProjectIdOrderByName(PROJECT)).thenReturn(List.of(folder));

        assertThat(access.canUpload(context(ProjectRole.ANALYST), folder)).isTrue();
        assertThat(access.canUpload(context(ProjectRole.OBSERVER), folder)).isFalse();
        assertThat(access.canUpload(archived(ProjectRole.PROJECT_MANAGER), folder)).isFalse();

        when(jdbc.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        assertThat(access.uploadableFolder(context(ProjectRole.TARGET), folder.getId())).isSameAs(folder);
        when(jdbc.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class))).thenReturn(false);
        assertThatThrownBy(() -> access.uploadableFolder(context(ProjectRole.TARGET), folder.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }
}
