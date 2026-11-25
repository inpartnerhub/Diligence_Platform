package com.inpartnerhub.diligence.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.inpartnerhub.diligence.TestContainersConfiguration;
import com.inpartnerhub.diligence.search.SearchIndex.IndexedDocument;
import com.inpartnerhub.diligence.search.SearchIndex.SearchHit;
import com.inpartnerhub.diligence.search.SearchIndex.SearchQuery;
import com.inpartnerhub.diligence.security.Role;

/**
 * Full-text search against real PostgreSQL ({@code dr.document_search_index}, GIN index, Indonesian/English/simple
 * dictionaries, {@code ts_headline} highlights, and row-level security).
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import(TestContainersConfiguration.class)
class PostgresSearchIndexIntegrationTest {

    private static final Instant BEFORE_RESTORE = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant RUN_START = Instant.parse("2026-11-08T10:00:00Z");

    @Autowired
    JdbcTemplate app;

    @Autowired
    PlatformTransactionManager txManager;

    JdbcTemplate ownerJdbc;

    @Autowired
    void ownerDatabase(TestContainersConfiguration.OwnerDatabase owner) {
        this.ownerJdbc = owner.jdbc();
    }

    private final UUID project = UUID.randomUUID();
    private final UUID folder = UUID.randomUUID();
    private final UUID otherFolder = UUID.randomUUID();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sweepRemovesWhatTheRunDidNotRefresh() {
        var before = indexAt(BEFORE_RESTORE);
        var during = indexAt(RUN_START.plusSeconds(30));

        var deletedSinceRestore = document("gone", "perjanjian sewa gudang");
        var current = document("current", "akta pendirian perseroan");
        before.index(deletedSinceRestore);
        before.index(current);

        var uploadedDuringRun = document("new", "kontrak distribusi baru");
        assertThat(during.indexAll(List.of(current))).isEmpty();
        during.index(uploadedDuringRun);

        var removed = during.removeIndexedBefore(RUN_START.minusSeconds(60));

        assertThat(removed).isEqualTo(1);
        assertThat(hitIds(during, "perjanjian")).isEmpty();
        assertThat(hitIds(during, "akta")).containsExactly(current.documentId());
        assertThat(hitIds(during, "kontrak")).containsExactly(uploadedDuringRun.documentId());
    }

    @Test
    void anOlderVersionNeverReplacesANewerOne() {
        var index = indexAt(RUN_START);
        var documentId = UUID.randomUUID();
        var older = new IndexedDocument(documentId, UUID.randomUUID(), project, folder, "a.pdf", "application/pdf",
                "draf pertama", BEFORE_RESTORE);
        var newer = new IndexedDocument(documentId, UUID.randomUUID(), project, folder, "a.pdf", "application/pdf",
                "versi final", BEFORE_RESTORE.plusSeconds(3600));

        index.index(newer);
        assertThat(index.indexAll(List.of(older))).isEmpty();
        index.index(older);
        index.index(newer);

        assertThat(hitIds(index, "final")).containsExactly(documentId);
        assertThat(hitIds(index, "draf")).isEmpty();

        index.remove(documentId);
        index.remove(UUID.randomUUID());
        assertThat(hitIds(index, "final")).isEmpty();
    }

    @Test
    void removeProjectDeletesOnlyThatProjectsEntries() {
        var index = indexAt(RUN_START);
        var purged = document("a", "perjanjian kredit");
        var otherProject = UUID.randomUUID();
        var kept = new IndexedDocument(UUID.randomUUID(), UUID.randomUUID(), otherProject, folder, "b.pdf",
                "application/pdf", "perjanjian kredit", BEFORE_RESTORE);
        index.index(purged);
        index.index(kept);

        index.removeProject(project);
        index.removeProject(project);

        assertThat(hitIds(index, "perjanjian")).isEmpty();
        var otherHits = searchAsSystem(index, new SearchQuery(otherProject, List.of(folder), "perjanjian", 0, 10));
        assertThat(otherHits.hits()).extracting(SearchHit::documentId).containsExactly(kept.documentId());
    }

    @Test
    void searchSupportsDictionariesHighlightsFolderBarriersAndRls() {
        var index = indexAt(RUN_START);
        var indonesianDoc = new IndexedDocument(UUID.randomUUID(), UUID.randomUUID(), project, folder,
                "Ringkasan.pdf", "application/pdf",
                "Para pihak menandatangani perjanjian kerja sama pada tahun ini.", BEFORE_RESTORE);
        var englishDoc = new IndexedDocument(UUID.randomUUID(), UUID.randomUUID(), project, folder,
                "Agreement.pdf", "application/pdf",
                "The parties agreed to extend the lease agreements.", BEFORE_RESTORE.plusSeconds(10));
        var restrictedFolderDoc = new IndexedDocument(UUID.randomUUID(), UUID.randomUUID(), project, otherFolder,
                "Secret.pdf", "application/pdf",
                "Perjanjian rahasia internal.", BEFORE_RESTORE.plusSeconds(20));
        index.indexAll(List.of(indonesianDoc, englishDoc, restrictedFolderDoc));

        // Exact term + highlights with MATCH_START/MATCH_END
        var idPage = searchAsSystem(index, new SearchQuery(project, List.of(folder), "perjanjian", 0, 10));
        assertThat(idPage.total()).isEqualTo(1);
        assertThat(idPage.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.documentId()).isEqualTo(indonesianDoc.documentId());
            assertThat(hit.highlights()).isNotEmpty();
            assertThat(hit.highlights().getFirst())
                    .contains(SearchHit.MATCH_START + "perjanjian" + SearchHit.MATCH_END);
        });

        // Indonesian stemming ("janji" matches "perjanjian") & English stemming ("agreement" matches "agreements")
        assertThat(hitIds(index, "janji")).containsExactly(indonesianDoc.documentId());
        assertThat(hitIds(index, "agreement")).containsExactly(englishDoc.documentId());

        // Folder filter excludes otherFolder
        assertThat(hitIds(index, "rahasia")).isEmpty();

        // RLS: project member sees results; outsider sees nothing
        var analyst = UUID.randomUUID();
        var outsider = UUID.randomUUID();
        ownerJdbc.update("insert into prj.project (id, code, name, client_organization_id, target_organization_id, "
                + "created_by) values (?, ?, 'Search', ?, ?, ?)",
                project, "SRCH-" + project.toString().substring(0, 8), UUID.randomUUID(), UUID.randomUUID(), analyst);
        ownerJdbc.update("insert into prj.project_member (id, project_id, user_id, project_role) "
                + "values (?, ?, ?, 'ANALYST')", UUID.randomUUID(), project, analyst);

        signIn(analyst, Role.ANALYST);
        var memberPage = new TransactionTemplate(txManager)
                .execute(s -> index.search(new SearchQuery(project, List.of(folder), "perjanjian", 0, 10)));
        assertThat(memberPage.hits()).extracting(SearchHit::documentId).containsExactly(indonesianDoc.documentId());

        signIn(outsider, Role.ANALYST);
        var outsiderPage = new TransactionTemplate(txManager)
                .execute(s -> index.search(new SearchQuery(project, List.of(folder), "perjanjian", 0, 10)));
        assertThat(outsiderPage.hits()).isEmpty();
    }

    private PostgresSearchIndex indexAt(Instant instant) {
        return new PostgresSearchIndex(app, txManager, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private IndexedDocument document(String name, String content) {
        return new IndexedDocument(UUID.randomUUID(), UUID.randomUUID(), project, folder, name + ".pdf",
                "application/pdf", content, BEFORE_RESTORE);
    }

    private List<UUID> hitIds(PostgresSearchIndex index, String text) {
        return searchAsSystem(index, new SearchQuery(project, List.of(folder), text, 0, 10))
                .hits().stream().map(SearchHit::documentId).toList();
    }

    private PostgresSearchIndex.SearchPage searchAsSystem(PostgresSearchIndex index, SearchQuery query) {
        SecurityContextHolder.clearContext();
        return new TransactionTemplate(txManager).execute(status -> index.search(query));
    }

    private static void signIn(UUID userId, Role role) {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject(userId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority(role.authority())), userId.toString()));
    }
}
