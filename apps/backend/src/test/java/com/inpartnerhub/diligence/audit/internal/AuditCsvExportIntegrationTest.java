package com.inpartnerhub.diligence.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;

import com.inpartnerhub.diligence.TestContainersConfiguration;

/**
 * Issue #116 against PostgreSQL: the CSV export pages through the audit log by (occurred_at, id) and writes every
 * matching entry exactly once, newest first, across several batches (including ties on occurred_at); the action
 * filter uses the new index.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import(TestContainersConfiguration.class)
class AuditCsvExportIntegrationTest {

    @Autowired
    AuditCsvExport export;

    /** The schema owner's connection: seeds and checks data outside row-level security (#177). */
    JdbcTemplate jdbc;

    @Autowired
    void ownerDatabase(TestContainersConfiguration.OwnerDatabase owner) {
        jdbc = owner.jdbc();
    }

    @Test
    void streamsEveryMatchingEntryOnceAcrossBatches() throws Exception {
        var action = "test.export-" + UUID.randomUUID();
        var base = Instant.parse("2026-11-01T00:00:00Z");
        int rows = AuditCsvExport.BATCH * 2 + 345;
        for (int i = 0; i < rows; i++) {
            // Groups of three share a timestamp, so the id breaks ties at batch boundaries.
            jdbc.update("insert into audit.audit_log (id, occurred_at, action, object_type, object_id, outcome) "
                    + "values (?, ?, ?, 'test', ?, 'SUCCESS')", UUID.randomUUID(),
                    Timestamp.from(base.plusSeconds(i / 3)), action, "obj-" + i);
        }
        jdbc.update("insert into audit.audit_log (id, action, object_type, object_id, outcome) "
                + "values (?, 'other.action', 'test', 'not-exported', 'SUCCESS')", UUID.randomUUID());

        var out = new ByteArrayOutputStream();
        Specification<AuditLogEntry> byAction = (root, query, cb) -> cb.equal(root.get("action"), action);
        export.write(byAction, out);

        var lines = out.toString(StandardCharsets.UTF_8).split("\r\n");
        assertThat(lines[0]).isEqualTo("﻿" + AuditCsvExport.HEADER.strip());
        assertThat(lines).hasSize(rows + 1);
        var objectIds = java.util.Arrays.stream(lines).skip(1).map(l -> l.split(",")[4]).toList();
        assertThat(objectIds).doesNotHaveDuplicates().doesNotContain("\"not-exported\"");
        assertThat(lines[1]).startsWith("\"" + base.plusSeconds((rows - 1) / 3)); // newest first
    }

    @Test
    void searchingByActionUsesTheIndex() {
        jdbc.execute("ANALYZE audit.audit_log");
        jdbc.execute("SET enable_seqscan = off");
        try {
            var plan = String.join("\n", jdbc.queryForList(
                    "EXPLAIN SELECT * FROM audit.audit_log WHERE action = 'document.scan-infected' "
                            + "ORDER BY occurred_at DESC LIMIT 50", String.class));
            assertThat(plan).contains("audit_log_action_idx");
        } finally {
            jdbc.execute("SET enable_seqscan = on");
        }
    }
}
