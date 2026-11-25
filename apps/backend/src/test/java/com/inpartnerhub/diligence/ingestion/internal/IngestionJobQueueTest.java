package com.inpartnerhub.diligence.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import com.inpartnerhub.diligence.dataroom.DocumentUploaded;
import com.inpartnerhub.diligence.ingestion.internal.IngestionJobQueue.ClaimedJob;

class IngestionJobQueueTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final IngestionProperties properties = new IngestionProperties(
            URI.create("http://tika"), URI.create("http://gotenberg"), "localhost", 3310,
            null, Duration.ZERO, null, null, null, null, null,
            Duration.ofSeconds(2), Duration.ofMinutes(10));
    private final IngestionJobQueue queue = new IngestionJobQueue(jdbc, txManager, properties,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final DocumentUploaded event = new DocumentUploaded(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "projects/p/documents/d/v", "Akta.pdf", "application/pdf", 1024L);

    @Test
    void enqueuesPendingJobWithDeclaredTypeFallback() {
        var withoutType = new DocumentUploaded(event.projectId(), event.documentId(), event.versionId(),
                event.objectKey(), event.fileName(), null, event.sizeBytes());

        queue.enqueue(withoutType);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(UUID.class), eq(event.projectId()), eq(event.documentId()),
                eq(event.versionId()), eq(event.objectKey()), eq(event.fileName()), eq("application/octet-stream"),
                eq(1024L), eq(3), eq(Timestamp.from(NOW)), eq(Timestamp.from(NOW)), eq(Timestamp.from(NOW)));
        assertThat(sql.getValue()).contains("INSERT INTO dr.ingestion_job");
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimNextReclaimsStaleLocksThenClaimsWithSkipLocked() {
        var jobId = UUID.randomUUID();
        var claimed = new ClaimedJob(jobId, event);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenReturn(List.of(claimed));

        var result = queue.claimNext();

        assertThat(result).contains(claimed);
        var staleSql = ArgumentCaptor.forClass(String.class);
        var claimSql = ArgumentCaptor.forClass(String.class);
        var nowTs = Timestamp.from(NOW);
        var staleTs = Timestamp.from(NOW.minus(Duration.ofMinutes(10)));
        verify(jdbc).update(staleSql.capture(), eq(nowTs), eq(nowTs), eq(staleTs));
        verify(jdbc).query(claimSql.capture(), any(RowMapper.class), eq(nowTs), eq(nowTs), eq(nowTs));
        assertThat(staleSql.getValue()).contains("status = 'PROCESSING'").contains("locked_at < ?");
        assertThat(claimSql.getValue()).contains("FOR UPDATE SKIP LOCKED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimNextReturnsEmptyWhenNoJobsReady() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenReturn(List.of());

        assertThat(queue.claimNext()).isEmpty();
    }

    @Test
    void completeDeletesJobAndFailSchedulesRetryOrMarksFailed() {
        var jobId = UUID.randomUUID();
        queue.complete(jobId);
        verify(jdbc).update("DELETE FROM dr.ingestion_job WHERE id = ?", jobId);

        queue.fail(jobId, "   ");
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(Timestamp.from(NOW.plusSeconds(15))), eq("Unknown error"),
                eq(Timestamp.from(NOW)), eq(jobId));
        assertThat(sql.getValue()).contains("attempts < max_attempts").contains("PENDING").contains("FAILED");
    }

    @Test
    void workerDrainsQueueAndRecordsFailures() {
        var mockQueue = mock(IngestionJobQueue.class);
        var pipeline = mock(IngestionPipeline.class);
        var worker = new IngestionJobWorker(mockQueue, pipeline);
        var ok = new ClaimedJob(UUID.randomUUID(), event);
        var broken = new ClaimedJob(UUID.randomUUID(), event);

        when(mockQueue.claimNext())
                .thenReturn(Optional.of(ok))
                .thenReturn(Optional.of(broken))
                .thenReturn(Optional.empty());
        doThrow(new IllegalStateException("boom")).when(pipeline).onDocumentUploaded(event);
        // First job succeeds, second throws: override stubbing sequentially
        org.mockito.Mockito.doNothing().doThrow(new IllegalStateException("boom"))
                .when(pipeline).onDocumentUploaded(event);

        worker.poll();

        var order = inOrder(mockQueue, pipeline);
        order.verify(pipeline).onDocumentUploaded(ok.event());
        order.verify(mockQueue).complete(ok.id());
        order.verify(pipeline).onDocumentUploaded(broken.event());
        order.verify(mockQueue).fail(broken.id(), "boom");
        verify(mockQueue, never()).complete(broken.id());
    }

    @Test
    void ingestionPropertiesExposeDefaultsForQueueSettings() {
        var defaults = new IngestionProperties(null, null, null, 0, null, null);
        assertThat(defaults.pollInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.staleLockTimeout()).isEqualTo(Duration.ofMinutes(10));
    }
}
