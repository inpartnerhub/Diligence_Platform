package com.inpartnerhub.diligence.audit.internal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.shared.web.CsvCells;

/**
 * Writes the audit trail as CSV in batches (ADM-04, #116): newest first, {@link #BATCH} entries per query, paged by
 * the key (occurred_at, id) rather than an offset, so memory stays bounded however large the audit log is and each
 * query uses the (…, occurred_at DESC) indexes. Every batch is its own short read-only transaction (Spring Data
 * repository default), so a slow download does not hold a database connection.
 */
@Component
class AuditCsvExport {

    static final int BATCH = 1_000;

    static final String HEADER = "Timestamp (UTC),User ID,Action,Object Type,Object ID,IP Address,Outcome,Details\r\n";

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

    private final AuditLogRepository repository;

    AuditCsvExport(AuditLogRepository repository) {
        this.repository = repository;
    }

    void write(Specification<AuditLogEntry> filter, OutputStream out) throws IOException {
        var writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.write("﻿"); // UTF-8 BOM for Excel
        writer.write(HEADER);
        AuditLogEntry last = null;
        while (true) {
            var page = last == null ? filter : filter.and(olderThan(last));
            List<AuditLogEntry> batch = repository.findBy(page, q -> q.sortBy(NEWEST_FIRST).limit(BATCH).all());
            for (var entry : batch) {
                writeRow(writer, entry);
            }
            writer.flush();
            if (batch.size() < BATCH) {
                return;
            }
            last = batch.getLast();
        }
    }

    /** Entries after {@code last} in (occurred_at DESC, id DESC) order. */
    private static Specification<AuditLogEntry> olderThan(AuditLogEntry last) {
        var at = last.getOccurredAt();
        var id = last.getId();
        return (root, query, cb) -> cb.or(
                cb.lessThan(root.get("occurredAt"), at),
                cb.and(cb.equal(root.get("occurredAt"), at), cb.lessThan(root.get("id"), id)));
    }

    private static void writeRow(BufferedWriter writer, AuditLogEntry entry) throws IOException {
        writer.write(String.join(",",
                CsvCells.cell(entry.getOccurredAt() != null ? entry.getOccurredAt().toString() : ""),
                CsvCells.cell(entry.getUserId() != null ? entry.getUserId().toString() : ""),
                CsvCells.cell(entry.getAction()),
                CsvCells.cell(entry.getObjectType()),
                CsvCells.cell(entry.getObjectId()),
                CsvCells.cell(entry.getIpAddress()),
                CsvCells.cell(entry.getOutcome()),
                CsvCells.cell(entry.getDetails())));
        writer.write("\r\n");
    }
}
