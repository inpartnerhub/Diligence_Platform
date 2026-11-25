package com.inpartnerhub.diligence.search.internal;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.inpartnerhub.diligence.search.SearchIndex;

/**
 * PostgreSQL full-text search index backed by {@code dr.document_search_index} with a generated {@code TSVECTOR}
 * combining {@code simple}, {@code indonesian} and {@code english} configurations.
 */
@Component
class PostgresSearchIndex implements SearchIndex {

    private static final int BATCH_SIZE = 500;
    private static final String FRAGMENT_DELIMITER = "\u001F";

    private static final String UPSERT_SQL = """
            INSERT INTO dr.document_search_index
                (document_id, version_id, project_id, folder_id, name, content_type, content, uploaded_at, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (document_id) DO UPDATE SET
                version_id = EXCLUDED.version_id,
                project_id = EXCLUDED.project_id,
                folder_id = EXCLUDED.folder_id,
                name = EXCLUDED.name,
                content_type = EXCLUDED.content_type,
                content = EXCLUDED.content,
                uploaded_at = EXCLUDED.uploaded_at,
                indexed_at = EXCLUDED.indexed_at
            WHERE dr.document_search_index.uploaded_at <= EXCLUDED.uploaded_at
            """;

    private static final String SEARCH_SQL = """
            WITH q AS (
                SELECT websearch_to_tsquery('simple', ?) AS q_simple,
                       websearch_to_tsquery('indonesian', ?) AS q_id,
                       websearch_to_tsquery('english', ?) AS q_en
            )
            SELECT i.document_id,
                   i.version_id,
                   i.folder_id,
                   i.name,
                   ts_rank_cd(i.search_vector, q.q_simple || q.q_id || q.q_en) AS score,
                   CASE
                       WHEN i.content = '' THEN ''
                       ELSE ts_headline(
                           'simple',
                           i.content,
                           q.q_simple || q.q_id || q.q_en,
                           'StartSel=' || ? || ', StopSel=' || ? || ', MaxWords=30, MinWords=10, MaxFragments=3, FragmentDelimiter=' || chr(31)
                       )
                   END AS highlights,
                   COUNT(*) OVER() AS total_count
            FROM dr.document_search_index i
            CROSS JOIN q
            WHERE i.project_id = ?
              AND i.folder_id = ANY (?)
              AND (
                  i.search_vector @@ q.q_simple
                  OR i.search_vector @@ q.q_id
                  OR i.search_vector @@ q.q_en
              )
            ORDER BY score DESC, i.uploaded_at DESC
            LIMIT ? OFFSET ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    PostgresSearchIndex(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, Clock.systemUTC());
    }

    PostgresSearchIndex(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void index(IndexedDocument document) {
        var indexedAt = Timestamp.from(Instant.now(clock));
        transactions.executeWithoutResult(status -> jdbc.update(UPSERT_SQL, ps -> bindDocument(ps, document, indexedAt)));
    }

    @Override
    public List<UUID> indexAll(List<IndexedDocument> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        var indexedAt = Timestamp.from(Instant.now(clock));
        transactions.executeWithoutResult(status ->
                jdbc.batchUpdate(UPSERT_SQL, documents, BATCH_SIZE, (ps, doc) -> bindDocument(ps, doc, indexedAt)));
        return List.of();
    }

    @Override
    public void remove(UUID documentId) {
        transactions.executeWithoutResult(status ->
                jdbc.update("DELETE FROM dr.document_search_index WHERE document_id = ?", documentId));
    }

    @Override
    public void removeProject(UUID projectId) {
        transactions.executeWithoutResult(status ->
                jdbc.update("DELETE FROM dr.document_search_index WHERE project_id = ?", projectId));
    }

    @Override
    public long removeIndexedBefore(Instant cutoff) {
        var deleted = transactions.execute(status ->
                jdbc.update("DELETE FROM dr.document_search_index WHERE indexed_at < ?", Timestamp.from(cutoff)));
        return deleted == null ? 0L : deleted.longValue();
    }

    @Override
    public SearchPage search(SearchQuery query) {
        if (query.folderIds().isEmpty() || query.text() == null || query.text().isBlank()) {
            return new SearchPage(List.of(), 0);
        }
        var text = query.text().strip();
        var page = Math.max(query.page(), 0);
        var size = Math.max(query.size(), 1);
        var offset = (long) page * size;
        var folders = query.folderIds().toArray(UUID[]::new);

        var result = jdbc.query(SEARCH_SQL, ps -> {
            ps.setString(1, text);
            ps.setString(2, text);
            ps.setString(3, text);
            ps.setString(4, SearchHit.MATCH_START);
            ps.setString(5, SearchHit.MATCH_END);
            ps.setObject(6, query.projectId());
            ps.setArray(7, ps.getConnection().createArrayOf("uuid", folders));
            ps.setInt(8, size);
            ps.setLong(9, offset);
        }, rs -> {
            var hits = new ArrayList<SearchHit>();
            long total = 0;
            while (rs.next()) {
                total = rs.getLong("total_count");
                hits.add(new SearchHit(
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("version_id", UUID.class),
                        rs.getObject("folder_id", UUID.class),
                        rs.getString("name"),
                        rs.getDouble("score"),
                        parseHighlights(rs.getString("highlights"))));
            }
            return new SearchPage(hits, total);
        });
        return result == null ? new SearchPage(List.of(), 0) : result;
    }

    private static void bindDocument(PreparedStatement ps, IndexedDocument document, Timestamp indexedAt)
            throws SQLException {
        ps.setObject(1, document.documentId());
        ps.setObject(2, document.versionId());
        ps.setObject(3, document.projectId());
        ps.setObject(4, document.folderId());
        ps.setString(5, sanitize(document.name()));
        ps.setString(6, document.contentType());
        ps.setString(7, sanitize(document.content()));
        ps.setTimestamp(8, Timestamp.from(document.uploadedAt()));
        ps.setTimestamp(9, indexedAt);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace("\0", "");
    }

    static List<String> parseHighlights(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(FRAGMENT_DELIMITER))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.contains(SearchHit.MATCH_START))
                .limit(3)
                .toList();
    }
}
