package com.inpartnerhub.diligence.ingestion.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.inpartnerhub.diligence.dataroom.DocumentUploaded;

/**
 * PostgreSQL-backed ingestion job queue ({@code dr.ingestion_job}). Enqueues jobs inside the upload transaction and
 * claims them with {@code FOR UPDATE SKIP LOCKED} in the system RLS context.
 */
@Component
class IngestionJobQueue {

    private static final int RETRY_DELAY_SECONDS = 15;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    record ClaimedJob(UUID id, DocumentUploaded event) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IngestionProperties properties;
    private final Clock clock;

    @Autowired
    IngestionJobQueue(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            IngestionProperties properties) {
        this(jdbc, transactionManager, properties, Clock.systemUTC());
    }

    IngestionJobQueue(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            IngestionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener
    void enqueue(DocumentUploaded event) {
        var now = Timestamp.from(Instant.now(clock));
        var declaredType = Objects.requireNonNullElse(event.declaredContentType(), "application/octet-stream");
        jdbc.update("""
                INSERT INTO dr.ingestion_job
                    (id, project_id, document_id, version_id, object_key, file_name,
                     declared_content_type, size_bytes, status, attempts, max_attempts,
                     available_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), event.projectId(), event.documentId(), event.versionId(),
                event.objectKey(), event.fileName(), declaredType, event.sizeBytes(),
                DEFAULT_MAX_ATTEMPTS, now, now, now);
    }

    Optional<ClaimedJob> claimNext() {
        return transactions.execute(status -> {
            var nowInstant = Instant.now(clock);
            var now = Timestamp.from(nowInstant);
            var staleBefore = Timestamp.from(nowInstant.minus(properties.staleLockTimeout()));
            jdbc.update("""
                    UPDATE dr.ingestion_job
                    SET status = CASE WHEN attempts < max_attempts THEN 'PENDING' ELSE 'FAILED' END,
                        available_at = CASE WHEN attempts < max_attempts THEN ? ELSE available_at END,
                        locked_at = NULL,
                        last_error = CASE WHEN attempts >= max_attempts AND last_error IS NULL
                                          THEN 'Ingestion worker timed out' ELSE last_error END,
                        updated_at = ?
                    WHERE status = 'PROCESSING' AND locked_at < ?
                    """, now, now, staleBefore);

            var claimed = jdbc.query("""
                    WITH next_job AS (
                        SELECT id
                        FROM dr.ingestion_job
                        WHERE status = 'PENDING' AND available_at <= ?
                        ORDER BY available_at, created_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                    )
                    UPDATE dr.ingestion_job j
                    SET status = 'PROCESSING',
                        attempts = j.attempts + 1,
                        locked_at = ?,
                        updated_at = ?
                    FROM next_job
                    WHERE j.id = next_job.id
                    RETURNING j.id, j.project_id, j.document_id, j.version_id, j.object_key, j.file_name,
                              j.declared_content_type, j.size_bytes
                    """, this::mapClaimed, now, now, now);
            return claimed.stream().findFirst();
        });
    }

    void complete(UUID jobId) {
        transactions.executeWithoutResult(status -> jdbc.update("DELETE FROM dr.ingestion_job WHERE id = ?", jobId));
    }

    void fail(UUID jobId, String error) {
        var nowInstant = Instant.now(clock);
        var now = Timestamp.from(nowInstant);
        var retryAt = Timestamp.from(nowInstant.plusSeconds(RETRY_DELAY_SECONDS));
        var message = error == null || error.isBlank() ? "Unknown error" : error;
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE dr.ingestion_job
                SET status = CASE WHEN attempts < max_attempts THEN 'PENDING' ELSE 'FAILED' END,
                    available_at = CASE WHEN attempts < max_attempts THEN ? ELSE available_at END,
                    locked_at = NULL,
                    last_error = ?,
                    updated_at = ?
                WHERE id = ?
                """, retryAt, message, now, jobId));
    }

    private ClaimedJob mapClaimed(ResultSet rs, int row) throws SQLException {
        var event = new DocumentUploaded(
                rs.getObject("project_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getString("object_key"),
                rs.getString("file_name"),
                rs.getString("declared_content_type"),
                rs.getLong("size_bytes"));
        return new ClaimedJob(rs.getObject("id", UUID.class), event);
    }
}
