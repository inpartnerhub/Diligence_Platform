package com.inpartnerhub.diligence.dataroom.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;
import com.inpartnerhub.diligence.dataroom.internal.SearchReindexEvents.SearchReindexFinished;
import com.inpartnerhub.diligence.dataroom.internal.SearchReindexEvents.SearchReindexStarted;
import com.inpartnerhub.diligence.search.SearchIndex;
import com.inpartnerhub.diligence.search.SearchIndex.IndexedDocument;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.RequiresRecentSignIn;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;

/**
 * Rebuilds the search index from PostgreSQL (issue #80), e.g. after a point-in-time restore of the database or a lost
 * index. Every document's current CLEAN version is indexed again from its metadata and the text the ingestion
 * pipeline already extracted to object storage, so nothing is scanned, extracted or rendered a second time.
 * <p>
 * Afterwards entries the run did not refresh are removed: documents that no longer exist or are not CLEAN in the
 * database. Entries written by uploads while the run is going on carry a newer stamp and stay. When any document
 * could not be indexed the sweep is skipped, so a partial run never removes entries it could not replace.
 * <p>
 * One run at a time across replicas (partial unique index on {@code dr.search_reindex_run}); the work happens on a
 * background thread as the system, and its progress is kept in that table.
 */
@Service
class SearchReindexService {

    static final String RUNNING = "RUNNING";
    static final String COMPLETED = "COMPLETED";
    static final String FAILED = "FAILED";
    /** Margin for clock differences between replicas when deciding which entries the run refreshed. */
    static final Duration SWEEP_MARGIN = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(SearchReindexService.class);

    record Run(UUID id, String status, UUID requestedBy, Instant startedAt, Instant heartbeatAt, Instant finishedAt,
            long documentsTotal, long indexed, long failed, Long removed, String error) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SearchIndex index;
    private final DocumentStorage storage;
    private final CurrentUserProvider users;
    private final ApplicationEventPublisher events;
    private final SearchReindexProperties properties;
    private final Clock clock;
    private final ExecutorService worker;
    private final ExecutorService readers;

    @Autowired
    SearchReindexService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, SearchIndex index,
            DocumentStorage storage, CurrentUserProvider users, ApplicationEventPublisher events,
            SearchReindexProperties properties) {
        this(jdbc, transactionManager, index, storage, users, events, properties, Clock.systemUTC(),
                Executors.newSingleThreadExecutor(Thread.ofPlatform().name("search-reindex").factory()));
    }

    SearchReindexService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, SearchIndex index,
            DocumentStorage storage, CurrentUserProvider users, ApplicationEventPublisher events,
            SearchReindexProperties properties, Clock clock, ExecutorService worker) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.index = index;
        this.storage = storage;
        this.users = users;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.worker = worker;
        this.readers = Executors.newFixedThreadPool(properties.parallelism(),
                Thread.ofPlatform().name("search-reindex-read-", 0).factory());
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
        readers.shutdownNow();
    }

    /** Starts a run in the background; fails with 409 while another one is running. */
    @Transactional
    @RequiresRecentSignIn("re-indexing search")
    Run start() {
        var user = users.requireCurrentUser();
        var now = Instant.now(clock);
        // A run whose replica died never finishes: its heartbeat stops, so it no longer blocks a new one.
        jdbc.update("UPDATE dr.search_reindex_run SET status = 'FAILED', finished_at = ?, "
                + "error = 'Interrupted: no progress since ' || heartbeat_at WHERE status = 'RUNNING' "
                + "AND heartbeat_at < ?", Timestamp.from(now), Timestamp.from(now.minus(properties.staleAfter())));
        var id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO dr.search_reindex_run (id, status, requested_by, started_at, heartbeat_at) "
                    + "VALUES (?, 'RUNNING', ?, ?, ?)", id, user.id(), Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ex) {
            throw new ResourceConflictException("A search re-index is already running");
        }
        events.publishEvent(new SearchReindexStarted(id, RequestMetadata.capture()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.execute(() -> run(id, now));
            }
        });
        return new Run(id, RUNNING, user.id(), now, now, null, 0, 0, 0, null, null);
    }

    /** The most recent run, running or finished. */
    @Transactional(readOnly = true)
    Optional<Run> latest() {
        return jdbc.query("SELECT * FROM dr.search_reindex_run ORDER BY started_at DESC LIMIT 1", this::map)
                .stream().findFirst();
    }

    /** The run itself, on the worker thread (no user: the system role reads every project). */
    void run(UUID id, Instant startedAt) {
        long indexed = 0;
        long failed = 0;
        try {
            var total = transactions.execute(s -> jdbc.queryForObject("SELECT count(*) FROM dr.document d "
                    + "JOIN dr.document_version v ON v.id = d.current_version_id WHERE v.scan_status = 'CLEAN'",
                    Long.class));
            update(id, "documents_total = ?", total);
            UUID after = null;
            while (true) {
                var batch = nextBatch(after);
                if (batch.isEmpty()) {
                    break;
                }
                after = batch.getLast().documentId();
                var result = indexBatch(batch);
                indexed += result.indexed();
                failed += result.failed();
                update(id, "indexed = ?, failed = ?", indexed, failed);
            }
            Long removed = null;
            String error = null;
            if (failed == 0) {
                removed = index.removeIndexedBefore(startedAt.minus(SWEEP_MARGIN));
            } else {
                error = failed + " documents could not be indexed; stale entries were kept. Run it again.";
            }
            finish(id, COMPLETED, indexed, failed, removed, error);
            log.info("Search re-index {} completed: {} indexed, {} failed, {} removed", id, indexed, failed,
                    removed == null ? "none (sweep skipped)" : removed);
        } catch (RuntimeException ex) {
            log.error("Search re-index {} failed", id, ex);
            finish(id, FAILED, indexed, failed, null, ex.getMessage());
        }
    }

    private record Candidate(UUID documentId, UUID versionId, UUID projectId, UUID folderId, String name,
            String contentType, boolean textExtracted, Instant uploadedAt) {
    }

    private record BatchResult(long indexed, long failed) {
    }

    private static final String CANDIDATES = "SELECT d.id, v.id AS version_id, d.project_id, d.folder_id, d.name, "
            + "coalesce(v.detected_content_type, v.content_type) AS content_type, v.text_extracted, v.uploaded_at "
            + "FROM dr.document d JOIN dr.document_version v ON v.id = d.current_version_id "
            + "WHERE v.scan_status = 'CLEAN' ";

    /** Keyset pagination by document id: stable while documents are added or removed during the run. */
    private List<Candidate> nextBatch(UUID after) {
        return transactions.execute(s -> after == null
                ? jdbc.query(CANDIDATES + "ORDER BY d.id LIMIT ?", this::candidate, properties.batchSize())
                : jdbc.query(CANDIDATES + "AND d.id > ? ORDER BY d.id LIMIT ?", this::candidate, after,
                        properties.batchSize()));
    }

    private Candidate candidate(ResultSet rs, int row) throws SQLException {
        return new Candidate(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("folder_id", UUID.class), rs.getString("name"),
                rs.getString("content_type"), rs.getBoolean("text_extracted"),
                rs.getTimestamp("uploaded_at").toInstant());
    }

    /** Reads the extracted texts in parallel, then indexes in bulk requests of bounded size. */
    private BatchResult indexBatch(List<Candidate> batch) {
        var reads = batch.stream().map(c -> CompletableFuture.supplyAsync(() -> read(c), readers)).toList();
        long failed = 0;
        long indexed = 0;
        var bulk = new ArrayList<IndexedDocument>();
        long bulkChars = 0;
        for (var read : reads) {
            var document = read.join();
            if (document.isEmpty()) {
                failed++;
                continue;
            }
            var size = document.get().content().length();
            if (!bulk.isEmpty() && bulkChars + size > properties.bulkMaxSize().toBytes()) {
                var rejected = index.indexAll(List.copyOf(bulk)).size();
                indexed += bulk.size() - rejected;
                failed += rejected;
                bulk.clear();
                bulkChars = 0;
            }
            bulk.add(document.get());
            bulkChars += size;
        }
        if (!bulk.isEmpty()) {
            var rejected = index.indexAll(List.copyOf(bulk)).size();
            indexed += bulk.size() - rejected;
            failed += rejected;
        }
        return new BatchResult(indexed, failed);
    }

    private Optional<IndexedDocument> read(Candidate c) {
        try {
            var text = c.textExtracted() ? text(c) : "";
            return Optional.of(new IndexedDocument(c.documentId(), c.versionId(), c.projectId(), c.folderId(),
                    c.name(), c.contentType(), text, c.uploadedAt()));
        } catch (RuntimeException ex) {
            log.warn("Re-index: no text for document {} (version {}): {}", c.documentId(), c.versionId(),
                    ex.getMessage());
            return Optional.empty();
        }
    }

    private String text(Candidate c) {
        try (var content = storage.get(RenditionKeys.text(c.projectId(), c.versionId()))) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void update(UUID id, String assignments, Object... values) {
        var args = new ArrayList<>(List.of(values));
        args.add(Timestamp.from(Instant.now(clock)));
        args.add(id);
        transactions.executeWithoutResult(s -> jdbc.update("UPDATE dr.search_reindex_run SET " + assignments
                + ", heartbeat_at = ? WHERE id = ?", args.toArray()));
    }

    private void finish(UUID id, String status, long indexed, long failed, Long removed, String error) {
        var now = Timestamp.from(Instant.now(clock));
        transactions.executeWithoutResult(s -> {
            jdbc.update("UPDATE dr.search_reindex_run SET status = ?, indexed = ?, failed = ?, removed = ?, "
                    + "error = ?, finished_at = ?, heartbeat_at = ? WHERE id = ?",
                    status, indexed, failed, removed, error, now, now, id);
            events.publishEvent(new SearchReindexFinished(id, status, indexed, failed, removed,
                    RequestMetadata.SYSTEM));
        });
    }

    private Run map(ResultSet rs, int row) throws SQLException {
        var finished = rs.getTimestamp("finished_at");
        var removed = rs.getObject("removed", Long.class);
        return new Run(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getObject("requested_by", UUID.class), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("heartbeat_at").toInstant(), finished == null ? null : finished.toInstant(),
                rs.getLong("documents_total"), rs.getLong("indexed"), rs.getLong("failed"),
                removed, rs.getString("error"));
    }
}
