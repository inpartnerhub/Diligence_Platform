package com.inpartnerhub.diligence.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.PlatformTransactionManager;

import com.inpartnerhub.diligence.search.SearchIndex.IndexedDocument;
import com.inpartnerhub.diligence.search.SearchIndex.SearchHit;
import com.inpartnerhub.diligence.search.SearchIndex.SearchPage;
import com.inpartnerhub.diligence.search.SearchIndex.SearchQuery;

class PostgresSearchIndexTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final PostgresSearchIndex index = new PostgresSearchIndex(jdbc, txManager,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final IndexedDocument doc = new IndexedDocument(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "Akta.pdf", "application/pdf", "Perjanjian sewa", NOW.minusSeconds(60));

    @Test
    void indexAndIndexAllUpsertInsideTransaction() {
        index.index(doc);
        verify(jdbc).update(anyString(), any(PreparedStatementSetter.class));

        assertThat(index.indexAll(List.of())).isEmpty();
        assertThat(index.indexAll(List.of(doc))).isEmpty();
        verify(jdbc).batchUpdate(anyString(), eq(List.of(doc)), eq(500),
                ArgumentMatchers.<ParameterizedPreparedStatementSetter<IndexedDocument>>any());
    }

    @Test
    void removeOperationsDeleteExpectedRows() {
        when(jdbc.update(anyString(), any(Timestamp.class))).thenReturn(4);

        index.remove(doc.documentId());
        index.removeProject(doc.projectId());
        var cutoff = NOW.minusSeconds(300);
        assertThat(index.removeIndexedBefore(cutoff)).isEqualTo(4L);

        verify(jdbc).update("DELETE FROM dr.document_search_index WHERE document_id = ?", doc.documentId());
        verify(jdbc).update("DELETE FROM dr.document_search_index WHERE project_id = ?", doc.projectId());
        verify(jdbc).update("DELETE FROM dr.document_search_index WHERE indexed_at < ?", Timestamp.from(cutoff));
    }

    @Test
    void searchSkipsDatabaseWhenFoldersOrTextEmpty() {
        assertThat(index.search(new SearchQuery(doc.projectId(), List.of(), "akta", 0, 10)).hits()).isEmpty();
        assertThat(index.search(new SearchQuery(doc.projectId(), List.of(doc.folderId()), null, 0, 10)).hits())
                .isEmpty();
        assertThat(index.search(new SearchQuery(doc.projectId(), List.of(doc.folderId()), "   ", 0, 10)).hits())
                .isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchExecutesParameterizedFullTextQuery() {
        var expected = new SearchPage(List.of(new SearchHit(doc.documentId(), doc.versionId(), doc.folderId(),
                doc.name(), 0.9, List.of(SearchHit.MATCH_START + "sewa" + SearchHit.MATCH_END))), 1);
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenReturn(expected);

        var result = index.search(new SearchQuery(doc.projectId(), List.of(doc.folderId()), "sewa", 0, 20));

        assertThat(result).isEqualTo(expected);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class));
        assertThat(sql.getValue())
                .contains("websearch_to_tsquery('simple', ?)")
                .contains("websearch_to_tsquery('indonesian', ?)")
                .contains("websearch_to_tsquery('english', ?)")
                .contains("ts_rank_cd")
                .contains("ts_headline")
                .contains("COUNT(*) OVER()");
    }

    @Test
    void parseHighlightsSplitsFiltersAndCapsAtThreeFragments() {
        assertThat(PostgresSearchIndex.parseHighlights(null)).isEmpty();
        assertThat(PostgresSearchIndex.parseHighlights("   ")).isEmpty();
        assertThat(PostgresSearchIndex.parseHighlights("unhighlighted intro without markers")).isEmpty();

        var m1 = SearchHit.MATCH_START + "one" + SearchHit.MATCH_END;
        var m2 = SearchHit.MATCH_START + "two" + SearchHit.MATCH_END;
        var m3 = SearchHit.MATCH_START + "three" + SearchHit.MATCH_END;
        var m4 = SearchHit.MATCH_START + "four" + SearchHit.MATCH_END;
        var raw = "  " + m1 + " \u001F\u001F plain \u001F " + m2 + " \u001F " + m3 + " \u001F " + m4;

        assertThat(PostgresSearchIndex.parseHighlights(raw)).containsExactly(m1, m2, m3);
    }
}
