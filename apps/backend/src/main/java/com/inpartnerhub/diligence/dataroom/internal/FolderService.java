package com.inpartnerhub.diligence.dataroom.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.dataroom.DataRoomEvents.FolderChanged;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectCreated;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;

/** Folder tree and access grants per project (PRD DOC-01). */
@Service
@Transactional
class FolderService {

    record GrantCommand(FolderAccess.PrincipalType type, UUID principalId, FolderAccess.Permission permission) {
    }

    static final int MAX_NAME_LENGTH = 200;

    private final FolderRepository folders;
    private final FolderAccessRepository grants;
    private final DocumentRepository documents;
    private final DataRoomAccess access;
    private final ApplicationEventPublisher events;

    FolderService(FolderRepository folders, FolderAccessRepository grants, DocumentRepository documents,
            DataRoomAccess access, ApplicationEventPublisher events) {
        this.folders = folders;
        this.grants = grants;
        this.documents = documents;
        this.access = access;
        this.events = events;
    }

    @Transactional(readOnly = true)
    List<Folder> tree(UUID projectId) {
        return access.visibleFolders(access.project(projectId));
    }

    Folder create(UUID projectId, UUID parentId, String name, UUID workstreamId) {
        var context = access.manageableProject(projectId);
        if (parentId != null) {
            access.folder(context, parentId);
        }
        var folder = createChecked(projectId, parentId, cleanName(name), workstreamId);
        events.publishEvent(new FolderChanged(projectId, folder.getId(), "created", RequestMetadata.capture()));
        return folder;
    }

    Folder update(UUID projectId, UUID folderId, String name, Boolean downloadAllowed, Boolean printAllowed) {
        var context = access.manageableProject(projectId);
        var folder = access.folder(context, folderId);
        if (name != null && !name.isBlank()) {
            var cleaned = cleanName(name);
            if (!cleaned.equalsIgnoreCase(folder.getName()) && sibling(projectId, folder.getParentId(), cleaned)) {
                throw new ResourceConflictException("A folder named '" + cleaned + "' already exists here");
            }
            folder.rename(cleaned);
        }
        folder.changePolicy(downloadAllowed, printAllowed);
        events.publishEvent(new FolderChanged(projectId, folderId, "updated", RequestMetadata.capture()));
        return folder;
    }

    void delete(UUID projectId, UUID folderId) {
        var context = access.manageableProject(projectId);
        var folder = access.folder(context, folderId);
        if (folders.existsByParentId(folderId) || documents.existsByFolderId(folderId)) {
            throw new ResourceConflictException("Only empty folders can be deleted");
        }
        grants.deleteByFolderId(folderId);
        folders.delete(folder);
        events.publishEvent(new FolderChanged(projectId, folderId, "deleted", RequestMetadata.capture()));
    }

    @Transactional(readOnly = true)
    List<FolderAccess> grants(UUID projectId, UUID folderId) {
        var context = access.project(projectId);
        if (!context.isInternal()) {
            throw new org.springframework.security.access.AccessDeniedException("Access grants are internal");
        }
        access.folder(context, folderId);
        return grants.findByFolderId(folderId);
    }

    /** Replaces the grants of a folder; they apply to its subfolders as well (row-level security, V7). */
    List<FolderAccess> replaceGrants(UUID projectId, UUID folderId, List<GrantCommand> commands) {
        var context = access.manageableProject(projectId);
        access.folder(context, folderId);
        grants.deleteByFolderId(folderId);
        grants.flush();
        var saved = new ArrayList<FolderAccess>();
        for (var command : commands) {
            var grant = switch (command.type()) {
                case ROLE -> FolderAccess.forTargetRole(projectId, folderId, command.permission());
                case USER, GROUP -> {
                    if (command.principalId() == null) {
                        throw new InvalidRequestException("User and group grants need a principal");
                    }
                    yield FolderAccess.forPrincipal(projectId, folderId, command.type(), command.principalId(),
                            command.permission());
                }
            };
            saved.add(grants.save(grant));
        }
        events.publishEvent(new FolderChanged(projectId, folderId, "access-changed", RequestMetadata.capture()));
        return saved;
    }

    /** Creates the template's folder structure for a new project (PRD DOC-01), inside the creation transaction. */
    @EventListener
    void onProjectCreated(ProjectCreated event) {
        for (var path : event.folders()) {
            Folder parent = null;
            for (var segment : segments(path)) {
                var parentId = parent == null ? null : parent.getId();
                var existing = parent == null ? folders.findRoot(event.projectId(), segment)
                        : folders.findChild(parentId, segment);
                parent = existing.orElseGet(() -> folders.save(new Folder(event.projectId(), parentId, segment,
                        null)));
            }
        }
    }

    /** Finds or creates the subfolders of a relative upload path below {@code parent} (DOC-02 folder upload). */
    Folder ensurePath(ProjectContext context, Folder parent, List<String> directories) {
        var current = parent;
        for (var name : directories) {
            var parentId = current.getId();
            current = folders.findChild(parentId, name)
                    .orElseGet(() -> folders.save(new Folder(context.projectId(), parentId, name, null)));
        }
        return current;
    }

    private Folder createChecked(UUID projectId, UUID parentId, String name, UUID workstreamId) {
        if (sibling(projectId, parentId, name)) {
            throw new ResourceConflictException("A folder named '" + name + "' already exists here");
        }
        return folders.save(new Folder(projectId, parentId, name, workstreamId));
    }

    private boolean sibling(UUID projectId, UUID parentId, String name) {
        return (parentId == null ? folders.findRoot(projectId, name) : folders.findChild(parentId, name)).isPresent();
    }

    static List<String> segments(String path) {
        var result = new ArrayList<String>();
        for (var part : path.replace('\\', '/').split("/")) {
            if (!part.isBlank()) {
                result.add(cleanName(part));
            }
        }
        return result;
    }

    /** Folder and file names: no path separators, control characters or dot-only names; at most 200 characters. */
    static String cleanName(String raw) {
        var name = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}/\\\\]", "").strip();
        if (name.isEmpty() || name.chars().allMatch(c -> c == '.')) {
            throw new InvalidRequestException("Invalid name: '" + raw + "'");
        }
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }
}
