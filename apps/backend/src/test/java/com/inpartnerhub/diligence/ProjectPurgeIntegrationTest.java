package com.inpartnerhub.diligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectDataPurgeRequested;
import com.inpartnerhub.diligence.search.SearchIndex;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/**
 * Project purge across modules (PRD ADM-06, #114): after {@link ProjectDataPurgeRequested} no table outside the
 * audit log holds rows of the project, except the project record itself, its workstreams and its (expired)
 * memberships. Every table with a {@code project_id} column is checked, so a new table that the purge forgets
 * fails this test. Another project's data stays untouched. A purge that cannot delete the documents' files is not
 * recorded as done and keeps the rows until a retry succeeds (#124).
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import(TestContainersConfiguration.class)
class ProjectPurgeIntegrationTest {

    /** Kept on purpose: the record that the project existed (and who had access until it was archived). */
    private static final Set<String> KEPT = Set.of("prj.project", "prj.workstream", "prj.project_member");

    /** The schema owner's connection: seeds and checks data outside row-level security (#177). */
    JdbcTemplate jdbc;

    @Autowired
    void ownerDatabase(TestContainersConfiguration.OwnerDatabase owner) {
        jdbc = owner.jdbc();
    }

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    FailedEventPublications failedEvents;

    // No S3 or OpenSearch in this test: the purge must reach them, so they are stubbed.
    @MockitoBean
    DocumentStorage storage;

    @MockitoBean
    SearchIndex searchIndex;

    private final UUID manager = UUID.randomUUID();
    private final UUID purged = UUID.randomUUID();
    private final UUID kept = UUID.randomUUID();

    @BeforeEach
    void seedTwoProjects() {
        // Seeded as the owner: row-level security applies only to the runtime role.
        for (var project : List.of(purged, kept)) {
            jdbc.update("insert into prj.project (id, code, name, client_organization_id, target_organization_id, "
                    + "created_by) values (?, ?, 'P', ?, ?, ?)", project, "PURGE-" + project, UUID.randomUUID(),
                    UUID.randomUUID(), manager);
            jdbc.update("insert into prj.project_member (id, project_id, user_id, project_role) "
                    + "values (?, ?, ?, 'PROJECT_MANAGER')", UUID.randomUUID(), project, manager);
            var workstream = UUID.randomUUID();
            jdbc.update("insert into prj.workstream (id, project_id, type) values (?, ?, 'LEGAL')", workstream,
                    project);
            jdbc.update("insert into prj.time_entry (project_id, workstream_type, user_id, entry_date, hours) "
                    + "values (?, 'LEGAL', ?, current_date, 2)", project, manager);

            var folder = UUID.randomUUID();
            jdbc.update("insert into dr.folder (id, project_id, name) values (?, ?, 'Contracts')", folder, project);
            jdbc.update("insert into dr.document (id, project_id, folder_id, name, content_type, size_bytes, sha256, "
                    + "scan_status, created_by) values (?, ?, ?, 'spa.pdf', 'application/pdf', 1, 'h', 'CLEAN', ?)",
                    UUID.randomUUID(), project, folder, manager);

            var finding = UUID.randomUUID();
            jdbc.update("insert into fnd.finding (id, project_id, workstream_id, number, title, category, "
                    + "description, status, created_by) values (?, ?, ?, 'F-LEG-001', 't', 'c', 'd', 'DRAFT', ?)",
                    finding, project, workstream, manager);
            jdbc.update("insert into fnd.finding_comment (id, finding_id, project_id, author_id, body) "
                    + "values (?, ?, ?, ?, 'internal note')", UUID.randomUUID(), finding, project, manager);

            var item = UUID.randomUUID();
            jdbc.update("insert into req.request_item (id, project_id, workstream_id, number, category, description) "
                    + "values (?, ?, ?, 'LEG-001', 'c', 'd')", item, project, workstream);
            jdbc.update("insert into req.request_item_status_history (id, request_item_id, project_id, to_status, "
                    + "wave) values (?, ?, ?, 'REQUESTED', 1)", UUID.randomUUID(), item, project);

            var question = UUID.randomUUID();
            jdbc.update("insert into qna.question (id, project_id, workstream_id, number, asked_by, body) "
                    + "values (?, ?, ?, 'Q-001', ?, 'b')", question, project, workstream, manager);
            jdbc.update("insert into qna.answer (question_id, project_id, body, answered_by) values (?, ?, 'a', ?)",
                    question, project, manager);

            var report = UUID.randomUUID();
            jdbc.update("insert into rpt.report (id, project_id, version_number, title, created_by) "
                    + "values (?, ?, 1, 'Report', ?)", report, project, manager);
            jdbc.update("insert into rpt.report_status_history (id, report_id, project_id, to_status, changed_by) "
                    + "values (?, ?, ?, 'DRAFT', ?)", UUID.randomUUID(), report, project, manager);

            jdbc.update("insert into ntf.notification (id, user_id, notification_type, project_id) "
                    + "values (?, ?, 'REPORT_ISSUED', ?)", UUID.randomUUID(), manager, project);
        }
    }

    @Test
    void purgeLeavesNoProjectDataOutsideTheProjectRecordAndTheAuditLog() {
        assertThat(leftovers(purged)).as("seeded data").isNotEmpty();
        var before = leftovers(kept);

        transactions.executeWithoutResult(status -> events.publishEvent(
                new ProjectDataPurgeRequested(purged, "PURGE-" + purged, RequestMetadata.SYSTEM)));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(leftovers(purged)).isEmpty());
        assertThat(leftovers(kept)).as("the other project").isEqualTo(before);
    }

    @Test
    void aPurgeThatCannotDeleteTheFilesKeepsTheDataAndIsNotRecordedUntilARetrySucceeds() {
        when(storage.deletePrefix(anyString())).thenThrow(new IllegalStateException("S3 refused the delete"));

        transactions.executeWithoutResult(status -> events.publishEvent(
                new ProjectDataPurgeRequested(purged, "PURGE-" + purged, RequestMetadata.SYSTEM)));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("select count(*) from event_publication where status = 'FAILED' "
                        + "and listener_id like '%DataRoomRetentionListener%'", Integer.class)).isPositive());
        assertThat(leftovers(purged)).containsKeys("dr.document", "dr.folder");
        assertThat(purgedRecords()).isZero();

        reset(storage);
        failedEvents.resubmit(ResubmissionOptions.defaults().withMinAge(Duration.ZERO));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(leftovers(purged)).isEmpty());
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(purgedRecords()).isEqualTo(1));
    }

    private Integer purgedRecords() {
        return jdbc.queryForObject("select count(*) from audit.audit_log where action = 'project.data-purged' "
                + "and object_id = ?", Integer.class, purged.toString());
    }

    /** Row counts per table with a project_id column, outside the audit schema and the kept tables. */
    private Map<String, Integer> leftovers(UUID project) {
        var tables = jdbc.queryForList("""
                select table_schema || '.' || table_name from information_schema.columns
                where column_name = 'project_id' and table_schema not in ('audit', 'information_schema', 'pg_catalog')
                  and table_name in (select table_name from information_schema.tables where table_type = 'BASE TABLE')
                """, String.class);
        var counts = new TreeMap<String, Integer>();
        for (var table : tables) {
            if (KEPT.contains(table)) {
                continue;
            }
            var count = jdbc.queryForObject("select count(*) from " + table + " where project_id = ?", Integer.class,
                    project);
            if (count != null && count > 0) {
                counts.put(table, count);
            }
        }
        return counts;
    }
}
