package com.inpartnerhub.diligence.search.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.analysis.Analyzer;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.query_dsl.Operator;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.search.HighlightField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.search.SearchIndex;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

/**
 * One index ({@code <prefix>-documents}) with the content analysed three ways: standard (exact words, numbers,
 * names), Indonesian and English stemming — data rooms mix both languages. The index is created on startup, or on
 * first use when OpenSearch was not reachable then.
 */
@Component
class OpenSearchIndex implements SearchIndex {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchIndex.class);
    private static final List<String> SEARCH_FIELDS = List.of("name^3", "name.id^2", "content", "content.id",
            "content.en");

    private final OpenSearchClient client;
    private final String index;
    private volatile boolean ready;

    OpenSearchIndex(OpenSearchClient client, SearchProperties properties) {
        this.client = client;
        this.index = properties.documentsIndex();
    }

    @EventListener(ApplicationReadyEvent.class)
    void createIndexOnStartup() {
        try {
            ensureIndex();
        } catch (RuntimeException ex) {
            log.warn("Search index {} not ready yet ({}); will retry on first use", index, ex.getMessage());
        }
    }

    @Override
    public void index(IndexedDocument document) {
        ensureIndex();
        var source = new HashMap<String, Object>();
        source.put("documentId", document.documentId().toString());
        source.put("versionId", document.versionId().toString());
        source.put("projectId", document.projectId().toString());
        source.put("folderId", document.folderId().toString());
        source.put("name", document.name());
        source.put("contentType", document.contentType());
        source.put("content", document.content() == null ? "" : document.content());
        source.put("uploadedAt", document.uploadedAt().toString());
        call(() -> client.index(i -> i.index(index).id(document.documentId().toString()).document(source)));
    }

    @Override
    public void remove(UUID documentId) {
        ensureIndex();
        try {
            call(() -> client.delete(d -> d.index(index).id(documentId.toString())));
        } catch (UpstreamServiceException ex) {
            if (!(ex.getCause() instanceof OpenSearchException os && os.status() == 404)) {
                throw ex; // a document that was never indexed is fine
            }
        }
    }

    @Override
    public SearchPage search(SearchQuery query) {
        if (query.folderIds().isEmpty() || query.text() == null || query.text().isBlank()) {
            return new SearchPage(List.of(), 0);
        }
        ensureIndex();
        var folders = query.folderIds().stream().map(id -> FieldValue.of(id.toString())).toList();
        var filter = List.of(
                Query.of(q -> q.term(t -> t.field("projectId").value(FieldValue.of(query.projectId().toString())))),
                Query.of(q -> q.terms(t -> t.field("folderId").terms(v -> v.value(folders)))));
        var match = Query.of(q -> q.simpleQueryString(s -> s.query(query.text().strip()).fields(SEARCH_FIELDS)
                .defaultOperator(Operator.And)
                .flags(f -> f.multiple("AND|OR|NOT|PHRASE|PREFIX|PRECEDENCE|WHITESPACE"))));
        var response = call(() -> client.search(s -> s.index(index)
                .query(q -> q.bool(b -> b.filter(filter).must(match)))
                .source(src -> src.filter(f -> f.includes(Entry.FIELDS)))
                .highlight(h -> h.preTags(SearchHit.MATCH_START).postTags(SearchHit.MATCH_END)
                        .fields("content", HighlightField.of(f -> f.fragmentSize(160).numberOfFragments(3)))
                        .fields("content.id", HighlightField.of(f -> f.fragmentSize(160).numberOfFragments(3)))
                        .fields("content.en", HighlightField.of(f -> f.fragmentSize(160).numberOfFragments(3))))
                .from(Math.max(query.page(), 0) * query.size())
                .size(query.size())
                .trackTotalHits(t -> t.enabled(true)), Entry.class));
        var hits = new ArrayList<SearchHit>();
        for (var hit : response.hits().hits()) {
            var source = hit.source();
            if (source == null) {
                continue;
            }
            var highlights = new ArrayList<String>();
            for (var field : List.of("content", "content.id", "content.en")) {
                for (var fragment : hit.highlight().getOrDefault(field, List.of())) {
                    if (highlights.size() < 3 && !highlights.contains(fragment)) {
                        highlights.add(fragment);
                    }
                }
            }
            hits.add(new SearchHit(UUID.fromString(source.documentId()), UUID.fromString(source.versionId()),
                    UUID.fromString(source.folderId()), source.name(),
                    hit.score() == null ? 0 : hit.score(), highlights));
        }
        var total = response.hits().total() == null ? hits.size() : response.hits().total().value();
        return new SearchPage(hits, total);
    }

    private void ensureIndex() {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (ready) {
                return;
            }
            var exists = call(() -> client.indices().exists(e -> e.index(index))).value();
            if (!exists) {
                createIndex();
            }
            ready = true;
        }
    }

    private void createIndex() {
        var keyword = Property.of(p -> p.keyword(k -> k));
        var text = Property.of(p -> p.text(t -> t.analyzer("standard")
                .fields("id", Property.of(f -> f.text(s -> s.analyzer("indonesian"))))
                .fields("en", Property.of(f -> f.text(s -> s.analyzer("english"))))));
        try {
            call(() -> client.indices().create(c -> c.index(index)
                    // One replica where the cluster has a second node; none on a single local node (stays green).
                    .settings(s -> s.autoExpandReplicas("0-1").analysis(a -> a.analyzer("indonesian",
                            Analyzer.of(an -> an.custom(cu -> cu.tokenizer("standard")
                                    .filter("lowercase", "indonesian_stemmer")))))
                            .analysis(a -> a.filter("indonesian_stemmer",
                                    f -> f.definition(d -> d.stemmer(st -> st.language("indonesian"))))))
                    .mappings(m -> m.properties("documentId", keyword)
                            .properties("versionId", keyword)
                            .properties("projectId", keyword)
                            .properties("folderId", keyword)
                            .properties("contentType", keyword)
                            .properties("uploadedAt", Property.of(p -> p.date(d -> d)))
                            .properties("name", text)
                            .properties("content", text))));
            log.info("Created search index {}", index);
        } catch (UpstreamServiceException ex) {
            if (!(ex.getCause() instanceof OpenSearchException os
                    && "resource_already_exists_exception".equals(os.error().type()))) {
                throw ex;
            }
        }
    }

    /** The stored fields a hit returns (the content itself is never sent back). */
    record Entry(String documentId, String versionId, String folderId, String name) {

        static final List<String> FIELDS = List.of("documentId", "versionId", "folderId", "name");
    }

    @FunctionalInterface
    private interface Call<T> {
        T run() throws IOException;
    }

    private static <T> T call(Call<T> call) {
        try {
            return call.run();
        } catch (IOException | OpenSearchException ex) {
            throw new UpstreamServiceException("Search is unavailable", ex);
        }
    }
}
