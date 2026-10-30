package com.inpartnerhub.diligence.dataroom;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to data-room documents for other modules (e.g. the request list links delivered documents, PRD REQ-03).
 * Results follow the caller's data-room permissions: folder grants (row-level security), workstream scope and the
 * client-side exclusion.
 */
public interface DocumentDirectory {

    /** Empty when the document does not exist in the project or the caller may not see it. */
    Optional<DocumentSummary> find(UUID projectId, UUID documentId);

    /** The documents among {@code documentIds} that the caller may see; unknown or hidden ones are left out. */
    List<DocumentSummary> findAll(UUID projectId, Collection<UUID> documentIds);

    /** @param scanStatus  PENDING, CLEAN, INFECTED, REJECTED or FAILED; only CLEAN documents can be opened */
    record DocumentSummary(UUID id, UUID folderId, String name, String contentType, long sizeBytes,
            String scanStatus) {
    }
}
