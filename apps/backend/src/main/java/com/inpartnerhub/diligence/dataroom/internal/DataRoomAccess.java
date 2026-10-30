package com.inpartnerhub.diligence.dataroom.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/**
 * Application-level data-room authorization on top of row-level security (which already hides folders the target
 * side was not granted): client users have no data room (PRD Bab 3), analysts are limited to their workstreams,
 * observers are read-only and archived projects are read-only.
 */
@Component
class DataRoomAccess {

    private final ProjectDirectory projects;
    private final FolderRepository folders;
    private final JdbcTemplate jdbc;

    DataRoomAccess(ProjectDirectory projects, FolderRepository folders, JdbcTemplate jdbc) {
        this.projects = projects;
        this.folders = folders;
        this.jdbc = jdbc;
    }

    ProjectContext project(UUID projectId) {
        var context = projects.context(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        if (!context.isInternal() && !context.hasAnyRole(ProjectRole.TARGET)) {
            throw new AccessDeniedException("The data room is not available to the client side");
        }
        return context;
    }

    ProjectContext writableProject(UUID projectId) {
        var context = project(projectId);
        if (context.archived()) {
            throw new ResourceConflictException("Project " + context.code() + " is archived");
        }
        return context;
    }

    ProjectContext manageableProject(UUID projectId) {
        var context = writableProject(projectId);
        if (!context.canManage()) {
            throw new AccessDeniedException("Only the project's partners and project managers manage folders");
        }
        return context;
    }

    /** Folders of the project visible to the caller, with analysts limited to their workstreams. */
    List<Folder> visibleFolders(ProjectContext context) {
        var all = folders.findByProjectIdOrderByName(context.projectId());
        var effective = effectiveWorkstreams(all);
        return all.stream().filter(f -> context.coversWorkstream(effective.get(f.getId()))).toList();
    }

    Folder folder(ProjectContext context, UUID folderId) {
        return visibleFolders(context).stream().filter(f -> f.getId().equals(folderId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Folder", folderId));
    }

    boolean canUpload(ProjectContext context, Folder folder) {
        if (context.archived()) {
            return false;
        }
        if (context.hasAnyRole(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER, ProjectRole.ANALYST)) {
            return true;
        }
        return context.hasAnyRole(ProjectRole.TARGET) && Boolean.TRUE.equals(
                jdbc.queryForObject("select dr.has_folder_access(?, true)", Boolean.class, folder.getId()));
    }

    Folder uploadableFolder(ProjectContext context, UUID folderId) {
        var folder = folder(context, folderId);
        if (!canUpload(context, folder)) {
            throw new AccessDeniedException("You cannot upload into this folder");
        }
        return folder;
    }

    /** A folder's workstream is its own, or inherited from the nearest ancestor that has one. */
    static Map<UUID, UUID> effectiveWorkstreams(List<Folder> folders) {
        var byId = new HashMap<UUID, Folder>();
        folders.forEach(f -> byId.put(f.getId(), f));
        var result = new HashMap<UUID, UUID>();
        for (var folder : folders) {
            var current = folder;
            UUID workstream = null;
            var depth = 0;
            while (current != null && depth++ < 64) {
                if (current.getWorkstreamId() != null) {
                    workstream = current.getWorkstreamId();
                    break;
                }
                current = current.getParentId() == null ? null : byId.get(current.getParentId());
            }
            result.put(folder.getId(), workstream);
        }
        return result;
    }
}
