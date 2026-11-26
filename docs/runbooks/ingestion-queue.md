# Document processing stuck / ingestion queue

Uploads are processed asynchronously through the PostgreSQL job queue `dr.ingestion_job` (ADR-0011): malware scan
(ClamAV) → type detection and text/OCR (Tika + Tesseract) → preview (Gotenberg/PDFBox) → search index
(DOC-02, DOC-03, DOC-06). Enqueue happens in the same transaction as the upload; the dedicated `diligence-ingestion`
Deployment claims jobs with `SELECT … FOR UPDATE SKIP LOCKED` and runs the pipeline. API pods do not process jobs
(`INGESTION_LISTENER_ENABLED=false`).

A job is claimed (`status = 'PROCESSING'`, `attempts` incremented, `locked_at` set), then deleted on success or, on an
unexpected error, set back to `PENDING` with a short delay — up to `max_attempts` (3), after which it becomes `FAILED`.
A worker that dies mid-job leaves the row `PROCESSING`; after `INGESTION_STALE_LOCK_TIMEOUT` (10 min) the next claim
reclaims it.

> **Single-server (compose).** There is no separate `diligence-ingestion` Deployment: the backend container is the
> queue consumer (`INGESTION_LISTENER_ENABLED=true` on `backend` in `compose.prod.yaml`). "No consumer" therefore
> means the backend is down or unhealthy — see [backend-unavailable.md](backend-unavailable.md). Use
> `docker compose -f compose.prod.yaml` in place of the `kubectl` commands below.

## Check

1. Queue depth and state:
   `SELECT status, count(*), min(created_at), max(updated_at) FROM dr.ingestion_job GROUP BY status`.
   A growing `PENDING` count with no `PROCESSING` means no worker is consuming.
2. Stuck locks: `SELECT id, document_id, version_id, attempts, locked_at, last_error FROM dr.ingestion_job
   WHERE status = 'PROCESSING' AND locked_at < now() - interval '10 minutes'`.
3. Permanently failed jobs: `SELECT id, document_id, version_id, attempts, last_error FROM dr.ingestion_job
   WHERE status = 'FAILED'`.
4. `diligence-ingestion` pod is running and ready; backend logs for `IngestionPipeline` /
   `Unexpected failure processing ingestion job`; health of ClamAV, Tika and Gotenberg.

## Mitigate

- No consumer: confirm the `diligence-ingestion` Deployment has a ready replica and
  `INGESTION_LISTENER_ENABLED=true`. Once it is back, `PENDING` jobs are claimed automatically.
- Dependency down (ClamAV updating signatures, Tika OOM): restart it; the worker retries the job automatically up to
  `max_attempts`. Stale `PROCESSING` locks are reclaimed after the timeout with no manual step.
- Retry a `FAILED` job after fixing the cause (processing is idempotent per version):
  `UPDATE dr.ingestion_job SET status = 'PENDING', attempts = 0, available_at = now(), locked_at = NULL,
  last_error = NULL WHERE id = '<job-id>'` (runs as the schema owner / a maintenance session; the runtime
  `diligence_app` role may only touch the queue in the `system` context).
- A single poison file (corrupt PDF, encrypted archive): leave the job `FAILED` and tell the uploader; the document
  stays unavailable because nothing is served before it is scanned CLEAN.

> Note: this queue has no dedicated Prometheus alert yet (the former `DiligenceMessagesStuck` /
> `DiligenceMessageBrokerDown` broker alerts were retired with RabbitMQ). Watch it through the `DiligenceEventsFailing`
> runbook ([failed-events.md](failed-events.md)) and the queries above; add a `dr.ingestion_job` backlog alert when a
> queue-depth metric is exported.
