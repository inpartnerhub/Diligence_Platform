package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Grant on a folder and its subfolders (PRD DOC-01): to the target role, a user or a user group. */
@Entity
@Table(schema = "dr", name = "folder_access")
class FolderAccess {

    enum PrincipalType { ROLE, USER, GROUP }

    enum Permission { VIEW, UPLOAD }

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "folder_id", nullable = false, updatable = false)
    private UUID folderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, updatable = false)
    private PrincipalType principalType;

    @Column(name = "project_role", updatable = false)
    private String projectRole;

    @Column(name = "principal_id", updatable = false)
    private UUID principalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Permission permission;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FolderAccess() {
    }

    private FolderAccess(UUID projectId, UUID folderId, PrincipalType type, String projectRole, UUID principalId,
            Permission permission) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.folderId = folderId;
        this.principalType = type;
        this.projectRole = projectRole;
        this.principalId = principalId;
        this.permission = permission;
        this.createdAt = Instant.now();
    }

    /** Grant to every target-side member of the project. */
    static FolderAccess forTargetRole(UUID projectId, UUID folderId, Permission permission) {
        return new FolderAccess(projectId, folderId, PrincipalType.ROLE, "TARGET", null, permission);
    }

    static FolderAccess forPrincipal(UUID projectId, UUID folderId, PrincipalType type, UUID principalId,
            Permission permission) {
        if (type == PrincipalType.ROLE) {
            throw new IllegalArgumentException("Use forTargetRole for role grants");
        }
        return new FolderAccess(projectId, folderId, type, null, principalId, permission);
    }

    UUID getId() {
        return id;
    }

    UUID getFolderId() {
        return folderId;
    }

    PrincipalType getPrincipalType() {
        return principalType;
    }

    String getProjectRole() {
        return projectRole;
    }

    UUID getPrincipalId() {
        return principalId;
    }

    Permission getPermission() {
        return permission;
    }
}
