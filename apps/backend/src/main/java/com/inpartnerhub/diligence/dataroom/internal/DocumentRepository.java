package com.inpartnerhub.diligence.dataroom.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByFolderIdOrderByName(UUID folderId);

    List<Document> findByFolderIdIn(Collection<UUID> folderIds);

    @Query("select d from Document d where d.folderId = :folderId and lower(d.name) = lower(:name)")
    Optional<Document> findByFolderAndName(UUID folderId, String name);

    /** Visible documents elsewhere in the project with identical content (duplicate detection, PRD Bab 8.3). */
    @Query("select d from Document d where d.projectId = :projectId and d.sha256 = :sha256 and d.id <> :excludeId")
    List<Document> findDuplicates(UUID projectId, String sha256, UUID excludeId);

    boolean existsByFolderId(UUID folderId);

    List<Document> findByProjectId(UUID projectId);

    long countByProjectId(UUID projectId);

    /** Project purge (ADM-06, #123): one statement; versions, annotations and clauses go by ON DELETE CASCADE. */
    @Modifying
    @Query("delete from Document d where d.projectId = :projectId")
    int deleteAllForProject(UUID projectId);
}
