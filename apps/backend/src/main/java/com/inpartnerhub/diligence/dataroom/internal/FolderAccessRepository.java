package com.inpartnerhub.diligence.dataroom.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface FolderAccessRepository extends JpaRepository<FolderAccess, UUID> {

    List<FolderAccess> findByFolderId(UUID folderId);

    void deleteByFolderId(UUID folderId);
}
