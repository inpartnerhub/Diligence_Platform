package com.inpartnerhub.diligence.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.inpartnerhub.diligence.search.SearchIndex.IndexedDocument;
import com.inpartnerhub.diligence.search.SearchIndex.SearchQuery;

/**
 * Issue #80 against a real OpenSearch (the managed domain runs 3.1): bulk indexing stamps entries, and the sweep after
 * a full re-index removes exactly the entries the run did not refresh — older stamps and entries from before the
 * stamp existed — while entries indexed during the run stay searchable.
 */
@Tag("integration")
@Testcontainers
class OpenSearchIndexIntegrationTest {

    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>("opensearchproject/opensearch:3.1.0")
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200));

    private static final Instant BEFORE_RESTORE = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant RUN_START = Instant.parse("2026-11-08T10:00:00Z");

    private final UUID project = UUID.randomUUID();
    private final UUID folder = UUID.randomUUID();
    private SearchProperties properties;
    private OpenSearchClient client;

    @BeforeEach
    void setUp() {
        var url = URI.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        properties = new SearchProperties(url, "it" + UUID.randomUUID().toString().substring(0, 8), null, null);
        client = new OpenSearchConfiguration().openSearchClient(properties);
    }

    @Test
    void sweepRemovesWhatTheRunDidNotRefresh() throws Exception {
        var before = new OpenSearchIndex(client, properties, at(BEFORE_RESTORE));
        var during = new OpenSearchIndex(client, properties, at(RUN_START.plusSeconds(30)));

        var deletedSinceRestore = document("gone", "perjanjian sewa gudang");
        var current = document("current", "akta pendirian perseroan");
        before.index(deletedSinceRestore);
        before.index(current);
        // Written before the stamp existed: no indexedAt at all.
        var legacy = UUID.randomUUID();
        client.index(i -> i.index(properties.documentsIndex()).id(legacy.toString()).document(Map.of(
                "documentId", legacy.toString(), "versionId", UUID.randomUUID().toString(),
                "projectId", project.toString(), "folderId", folder.toString(), "name", "legacy.pdf",
                "content", "laporan keuangan lama", "uploadedAt", BEFORE_RESTORE.toString())));

        // The run re-indexes what the restored database holds; an upload is indexed while it runs.
        var uploadedDuringRun = document("new", "kontrak distribusi baru");
        assertThat(during.indexAll(List.of(current))).isEmpty();
        during.index(uploadedDuringRun);

        var removed = during.removeIndexedBefore(RUN_START.minusSeconds(60));

        assertThat(removed).isEqualTo(2);
        assertThat(hits(during, "perjanjian")).isEmpty();
        assertThat(hits(during, "laporan")).isEmpty();
        assertThat(hits(during, "akta")).containsExactly(current.documentId());
        assertThat(hits(during, "kontrak")).containsExactly(uploadedDuringRun.documentId());
    }

    @Test
    void anOlderVersionNeverReplacesANewerOne() throws Exception {
        var index = new OpenSearchIndex(client, properties, at(RUN_START));
        var documentId = UUID.randomUUID();
        var older = new IndexedDocument(documentId, UUID.randomUUID(), project, folder, "a.pdf", "application/pdf",
                "draf pertama", BEFORE_RESTORE);
        var newer = new IndexedDocument(documentId, UUID.randomUUID(), project, folder, "a.pdf", "application/pdf",
                "versi final", BEFORE_RESTORE.plusSeconds(3600));

        index.index(newer);
        // A re-index that read the older version before the new upload, and a replayed event, arrive late.
        assertThat(index.indexAll(List.of(older))).isEmpty();
        index.index(older);
        // The same version again (a re-index of an unchanged document) is applied.
        index.index(newer);

        assertThat(hits(index, "final")).containsExactly(documentId);
        assertThat(hits(index, "draf")).isEmpty();

        index.remove(documentId);
        index.remove(UUID.randomUUID()); // never indexed: nothing to do, no error
        assertThat(hits(index, "final")).isEmpty();
    }

    @Test
    void removeProjectDeletesOnlyThatProjectsEntries() throws Exception {
        var index = new OpenSearchIndex(client, properties, at(RUN_START));
        var purged = document("a", "perjanjian kredit");
        var other = UUID.randomUUID();
        var kept = new IndexedDocument(UUID.randomUUID(), UUID.randomUUID(), other, folder, "b.pdf",
                "application/pdf", "perjanjian kredit", BEFORE_RESTORE);
        index.index(purged);
        index.index(kept);

        index.removeProject(project); // #123: one request for the whole project
        index.removeProject(project); // nothing left: no error

        assertThat(hits(index, "perjanjian")).isEmpty();
        client.indices().refresh(r -> r.index(properties.documentsIndex()));
        assertThat(index.search(new SearchQuery(other, List.of(folder), "perjanjian", 0, 10)).hits())
                .extracting(h -> h.documentId()).containsExactly(kept.documentId());
    }

    @Test
    void indexCreatedBeforeTheStampGetsTheField() throws Exception {
        // An index from an earlier release: same name, no indexedAt in the mapping.
        client.indices().create(c -> c.index(properties.documentsIndex()));
        var index = new OpenSearchIndex(client, properties, at(RUN_START));

        index.index(document("doc", "izin usaha"));

        var mapping = client.indices().getMapping(m -> m.index(properties.documentsIndex()))
                .get(properties.documentsIndex()).mappings().properties();
        assertThat(mapping.get(OpenSearchIndex.INDEXED_AT).isDate()).isTrue();
        assertThat(index.removeIndexedBefore(RUN_START.minusSeconds(60))).isZero();
    }

    private IndexedDocument document(String name, String content) {
        return new IndexedDocument(UUID.randomUUID(), UUID.randomUUID(), project, folder, name + ".pdf",
                "application/pdf", content, BEFORE_RESTORE);
    }

    private List<UUID> hits(OpenSearchIndex index, String text) throws Exception {
        client.indices().refresh(r -> r.index(properties.documentsIndex()));
        return index.search(new SearchQuery(project, List.of(folder), text, 0, 10)).hits().stream()
                .map(h -> h.documentId()).toList();
    }

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
