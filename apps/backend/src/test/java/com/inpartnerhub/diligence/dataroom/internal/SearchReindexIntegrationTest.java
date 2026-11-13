package com.inpartnerhub.diligence.dataroom.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.inpartnerhub.diligence.TestContainersConfiguration;
import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;
import com.inpartnerhub.diligence.search.SearchIndex;
import com.inpartnerhub.diligence.search.SearchIndex.IndexedDocument;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.StepUpRequiredException;

/**
 * Issue #80 against real PostgreSQL (row-level security, the one-running-run index): the run indexes every current
 * CLEAN version with the text already in object storage, sweeps afterwards, keeps stale entries when a document failed,
 * rejects a second run while one is going on and takes over from an interrupted one. OpenSearch is a mock here; its
 * side is covered by {@code OpenSearchIndexIntegrationTest}.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only",
        "diligence.dataroom.reindex.batch-size=2"
})
@Import(TestContainersConfiguration.class)
class SearchReindexIntegrationTest {

    @Autowired
    SearchReindexService reindex;

    /** The schema owner's connection: seeds and checks data outside row-level security (#177). */
    JdbcTemplate jdbc;

    @Autowired
    void ownerDatabase(TestContainersConfiguration.OwnerDatabase owner) {
        jdbc = owner.jdbc();
    }

    @MockitoBean
    SearchIndex searchIndex;

    @MockitoBean
    DocumentStorage storage;

    private static final String UNREADABLE = "<unreadable>";

    private final UUID admin = UUID.randomUUID();
    private final Map<String, String> texts = new ConcurrentHashMap<>();
    private final List<IndexedDocument> indexed = new CopyOnWriteArrayList<>();
    private UUID project;
    private UUID folder;

    @BeforeEach
    void setUp() {
        // Runs from other tests in this context must not block or confuse this one.
        jdbc.update("DELETE FROM dr.search_reindex_run");
        doAnswer(inv -> {
            indexed.addAll(inv.getArgument(0));
            return List.of();
        }).when(searchIndex).indexAll(anyList());
        // Documents of other tests have no text here: an empty text, not a failure.
        when(storage.get(anyString())).thenAnswer(inv -> {
            var text = texts.getOrDefault((String) inv.getArgument(0), "");
            if (UNREADABLE.equals(text)) {
                throw new IllegalStateException("object storage unavailable");
            }
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        });
        var client = UUID.randomUUID();
        var target = UUID.randomUUID();
        jdbc.update("insert into adm.organization (id, name, type) values (?, ?, 'CLIENT'), (?, ?, 'TARGET')",
                client, "C " + client, target, "T " + target);
        project = UUID.randomUUID();
        jdbc.update("insert into prj.project (id, code, name, client_organization_id, target_organization_id, "
                + "created_by) values (?, ?, 'Reindex', ?, ?, ?)", project, "RX-" + project.toString().substring(0, 8),
                client, target, admin);
        folder = UUID.randomUUID();
        jdbc.update("insert into dr.folder (id, project_id, name) values (?, ?, 'Docs')", folder, project);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        reset(searchIndex, storage);
    }

    @Test
    void indexesCurrentCleanVersionsWithStoredTextAndSweeps() {
        var contract = document("Kontrak.pdf", "CLEAN", true, "isi perjanjian");
        var scan = document("Scan.pdf", "CLEAN", false, null);
        var pending = document("Baru.pdf", "PENDING", false, null);
        var superseded = document("Lama.pdf", "CLEAN", true, "versi lama");
        newVersion(superseded, "PENDING");

        signIn(admin, Instant.now());
        var started = reindex.start();
        var run = awaitFinished(started.id());

        assertThat(run.status()).isEqualTo(SearchReindexService.COMPLETED);
        assertThat(run.failed()).isZero();
        assertThat(run.indexed()).isEqualTo(run.documentsTotal()).isGreaterThanOrEqualTo(2);
        var mine = indexed.stream().filter(d -> d.projectId().equals(project)).toList();
        assertThat(mine).extracting(IndexedDocument::documentId).containsExactlyInAnyOrder(contract, scan);
        assertThat(mine).filteredOn(d -> d.documentId().equals(contract)).singleElement()
                .satisfies(d -> {
                    assertThat(d.content()).isEqualTo("isi perjanjian");
                    assertThat(d.name()).isEqualTo("Kontrak.pdf");
                    assertThat(d.folderId()).isEqualTo(folder);
                });
        assertThat(mine).filteredOn(d -> d.documentId().equals(scan)).singleElement()
                .satisfies(d -> assertThat(d.content()).isEmpty());
        assertThat(indexed).extracting(IndexedDocument::documentId).doesNotContain(pending, superseded);
        // Entries the run did not refresh go; the cut-off leaves a margin before the start for clock skew.
        verify(searchIndex).removeIndexedBefore(started.startedAt().minus(SearchReindexService.SWEEP_MARGIN));
        assertThat(run.removed()).isZero(); // the mock removed nothing
        assertThat(reindex.latest()).get().extracting(SearchReindexService.Run::id).isEqualTo(started.id());
    }

    @Test
    void keepsStaleEntriesWhenADocumentFailed() {
        document("Rusak.pdf", "CLEAN", true, UNREADABLE);

        signIn(admin, Instant.now());
        var run = awaitFinished(reindex.start().id());

        assertThat(run.status()).isEqualTo(SearchReindexService.COMPLETED);
        assertThat(run.failed()).isEqualTo(1);
        assertThat(run.removed()).isNull();
        assertThat(run.error()).contains("could not be indexed");
        verify(searchIndex, never()).removeIndexedBefore(any());
    }

    @Test
    void oneRunAtATimeAndAnInterruptedRunIsTakenOver() throws Exception {
        document("Kontrak.pdf", "CLEAN", true, "isi");
        var release = new CountDownLatch(1);
        doAnswer(inv -> {
            release.await(30, TimeUnit.SECONDS);
            return List.of();
        }).when(searchIndex).indexAll(anyList());

        signIn(admin, Instant.now());
        var first = reindex.start();
        assertThatThrownBy(reindex::start).isInstanceOf(ResourceConflictException.class);
        release.countDown();
        assertThat(awaitFinished(first.id()).status()).isEqualTo(SearchReindexService.COMPLETED);

        // A replica died mid-run: its heartbeat stopped long ago, so a new run marks it failed and goes ahead.
        var dead = UUID.randomUUID();
        var longAgo = java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1)));
        jdbc.update("insert into dr.search_reindex_run (id, status, requested_by, started_at, heartbeat_at) "
                + "values (?, 'RUNNING', ?, ?, ?)", dead, admin, longAgo, longAgo);
        var next = reindex.start();
        assertThat(jdbc.queryForObject("select status from dr.search_reindex_run where id = ?", String.class, dead))
                .isEqualTo(SearchReindexService.FAILED);
        assertThat(awaitFinished(next.id()).status()).isEqualTo(SearchReindexService.COMPLETED);
    }

    @Test
    void requiresARecentSignIn() {
        signIn(admin, Instant.now().minus(Duration.ofHours(2)));

        assertThatThrownBy(reindex::start).isInstanceOf(StepUpRequiredException.class);
        assertThat(jdbc.queryForObject("select count(*) from dr.search_reindex_run", Long.class)).isZero();
    }

    private UUID document(String name, String status, boolean textExtracted, String text) {
        var id = UUID.randomUUID();
        jdbc.update("insert into dr.document (id, project_id, folder_id, name, content_type, size_bytes, sha256, "
                + "scan_status, created_by) values (?, ?, ?, ?, 'application/pdf', 1, ?, ?, ?)",
                id, project, folder, name, id.toString(), status, admin);
        var version = addVersion(id, 1, status, textExtracted);
        if (text != null) {
            texts.put(RenditionKeys.text(project, version), text);
        }
        return id;
    }

    private void newVersion(UUID document, String status) {
        addVersion(document, 2, status, false);
    }

    private UUID addVersion(UUID document, int number, String status, boolean textExtracted) {
        var version = UUID.randomUUID();
        jdbc.update("insert into dr.document_version (id, document_id, project_id, version_number, object_key, "
                + "file_name, content_type, detected_content_type, size_bytes, sha256, uploaded_by, scan_status, "
                + "text_extracted) values (?, ?, ?, ?, ?, 'f.pdf', 'application/pdf', ?, 1, ?, ?, ?, ?)",
                version, document, project, number, "projects/" + project + "/" + version,
                "CLEAN".equals(status) ? "application/pdf" : null, version.toString(), admin, status, textExtracted);
        jdbc.update("update dr.document set current_version_id = ? where id = ?", version, document);
        return version;
    }

    private SearchReindexService.Run awaitFinished(UUID id) {
        var deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            var run = jdbc.query("select status from dr.search_reindex_run where id = ?",
                    (rs, n) -> rs.getString(1), id);
            if (!run.isEmpty() && !SearchReindexService.RUNNING.equals(run.getFirst())) {
                return reindex.latest().filter(r -> r.id().equals(id)).orElseThrow();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("Re-index " + id + " did not finish");
    }

    private static void signIn(UUID userId, Instant authTime) {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject(userId.toString())
                .claim("auth_time", authTime.getEpochSecond()).claim("amr", List.of("pwd", "otp")).build(); // #160
        var authorities = new ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority(Role.SUPER_ADMIN.authority()));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities,
                userId.toString()));
    }
}
