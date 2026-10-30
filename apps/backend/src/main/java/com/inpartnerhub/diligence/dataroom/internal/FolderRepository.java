package com.inpartnerhub.diligence.dataroom.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Row-level security limits results to folders the caller may see. */
interface FolderRepository extends JpaRepository<Folder, UUID> {

    List<Folder> findByProjectIdOrderByName(UUID projectId);

    @Query("select f from Folder f where f.projectId = :projectId and f.parentId is null and lower(f.name) = lower(:name)")
    Optional<Folder> findRoot(UUID projectId, String name);

    @Query("select f from Folder f where f.parentId = :parentId and lower(f.name) = lower(:name)")
    Optional<Folder> findChild(UUID parentId, String name);

    boolean existsByParentId(UUID parentId);
}
