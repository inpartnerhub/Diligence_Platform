package com.inpartnerhub.diligence.dataroom.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}/folders")
@Tag(name = "Data room")
class FolderController {

    record FolderResponse(UUID id, UUID parentId, String name, UUID workstreamId, boolean downloadAllowed,
            boolean printAllowed, boolean canUpload) {
    }

    record CreateFolderRequest(@NotBlank @Size(max = 200) String name, UUID parentId, UUID workstreamId) {
    }

    record UpdateFolderRequest(@Size(max = 200) String name, Boolean downloadAllowed, Boolean printAllowed) {
    }

    record GrantDto(@NotNull FolderAccess.PrincipalType type, UUID principalId,
            @NotNull FolderAccess.Permission permission) {
    }

    record ReplaceGrantsRequest(@NotNull @Size(max = 200) List<@Valid GrantDto> grants) {
    }

    private final FolderService folders;
    private final DataRoomAccess access;

    FolderController(FolderService folders, DataRoomAccess access) {
        this.folders = folders;
        this.access = access;
    }

    @GetMapping
    @Operation(summary = "Folders the caller can see (target side: granted folders only) — DOC-01")
    List<FolderResponse> tree(@PathVariable UUID projectId) {
        var context = access.project(projectId);
        return folders.tree(projectId).stream().map(f -> new FolderResponse(f.getId(), f.getParentId(), f.getName(),
                f.getWorkstreamId(), f.isDownloadAllowed(), f.isPrintAllowed(), access.canUpload(context, f))).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a folder (project managers and partners) — DOC-01")
    FolderResponse create(@PathVariable UUID projectId, @Valid @RequestBody CreateFolderRequest request) {
        return response(folders.create(projectId, request.parentId(), request.name(), request.workstreamId()));
    }

    @PatchMapping("/{folderId}")
    @Operation(summary = "Rename a folder or change its download/print policy — DOC-01, DOC-05")
    FolderResponse update(@PathVariable UUID projectId, @PathVariable UUID folderId,
            @Valid @RequestBody UpdateFolderRequest request) {
        return response(folders.update(projectId, folderId, request.name(), request.downloadAllowed(),
                request.printAllowed()));
    }

    @DeleteMapping("/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an empty folder")
    void delete(@PathVariable UUID projectId, @PathVariable UUID folderId) {
        folders.delete(projectId, folderId);
    }

    @GetMapping("/{folderId}/access")
    @Operation(summary = "Access grants of a folder (internal team only) — DOC-01")
    List<GrantDto> grants(@PathVariable UUID projectId, @PathVariable UUID folderId) {
        return folders.grants(projectId, folderId).stream()
                .map(g -> new GrantDto(g.getPrincipalType(), g.getPrincipalId(), g.getPermission())).toList();
    }

    @PutMapping("/{folderId}/access")
    @Operation(summary = "Replace the access grants of a folder; they apply to subfolders too — DOC-01")
    List<GrantDto> replaceGrants(@PathVariable UUID projectId, @PathVariable UUID folderId,
            @Valid @RequestBody ReplaceGrantsRequest request) {
        var commands = request.grants().stream()
                .map(g -> new FolderService.GrantCommand(g.type(), g.principalId(), g.permission())).toList();
        return folders.replaceGrants(projectId, folderId, commands).stream()
                .map(g -> new GrantDto(g.getPrincipalType(), g.getPrincipalId(), g.getPermission())).toList();
    }

    private FolderResponse response(Folder f) {
        return new FolderResponse(f.getId(), f.getParentId(), f.getName(), f.getWorkstreamId(), f.isDownloadAllowed(),
                f.isPrintAllowed(), true);
    }
}
