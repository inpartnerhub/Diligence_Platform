package com.inpartnerhub.diligence.dataroom.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Row-level security limits results to folders the caller may see. */
interface FolderRepository extends JpaRepository<Folder, UUID> {

    List<Folder> findByProjectIdOrderByName(UUID projectId);

    @Query("select f from Folder f where f.projectId = :projectId and f.parentId is null and lower(f.name) = lower(:name)")
    Optional<Folder> findRoot(UUID projectId, String name);

    @Query("select f from Folder f where f.parentId = :parentId and lower(f.name) = lower(:name)")
    Optional<Folder> findChild(UUID parentId, String name);

    /**
     * The folder and its ancestors, as far as row-level security lets the caller see them (#128): what a single-folder
     * authorization needs, instead of every folder of the project. Order is not defined; follow {@code parentId}.
     */
    @Query(value = """
            WITH RECURSIVE chain (id, parent_id, depth) AS (
                SELECT f.id, f.parent_id, 0 FROM dr.folder f WHERE f.id = :folderId AND f.project_id = :projectId
                UNION ALL
                SELECT p.id, p.parent_id, c.depth + 1 FROM dr.folder p JOIN chain c ON p.id = c.parent_id
                WHERE c.depth < 64)
            SELECT f.* FROM dr.folder f WHERE f.id IN (SELECT id FROM chain)
            """, nativeQuery = true)
    List<Folder> findWithAncestors(UUID projectId, UUID folderId);

    boolean existsByParentId(UUID parentId);

    /**
     * Project purge (ADM-06, #123): one statement, after the documents; grants go by ON DELETE CASCADE. Parent links
     * are checked at the end of the statement, so the whole tree goes at once.
     */
    @Modifying
    @Query("delete from Folder f where f.projectId = :projectId")
    int deleteAllForProject(UUID projectId);
}
