package com.inpartnerhub.diligence.search;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Port for full-text search over document content (PRD DOC-06), backed by OpenSearch. The index holds one entry per
 * document (its current clean version). It does not authorize: callers pass the folders the user may see, and every
 * query is restricted to one project and those folders (information barrier, PRD Bab 3).
 */
public interface SearchIndex {

    void index(IndexedDocument document);

    void remove(UUID documentId);

    SearchPage search(SearchQuery query);

    record IndexedDocument(UUID documentId, UUID versionId, UUID projectId, UUID folderId, String name,
            String contentType, String content, Instant uploadedAt) {
    }

    /** {@code text} supports quoted phrases, {@code -exclusions} and {@code OR} (simple query string syntax). */
    record SearchQuery(UUID projectId, Collection<UUID> folderIds, String text, int page, int size) {
    }

    /** Highlight fragments mark matches with {@link #MATCH_START} and {@link #MATCH_END}. */
    record SearchHit(UUID documentId, UUID versionId, UUID folderId, String name, double score,
            List<String> highlights) {

        public static final String MATCH_START = "";
        public static final String MATCH_END = "";
    }

    record SearchPage(List<SearchHit> hits, long total) {
    }
}
