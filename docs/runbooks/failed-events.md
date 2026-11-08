# Failed event listeners

Modules react to each other through events (Spring Modulith, transactional outbox in `public.event_publication`):
search indexing after a scan, notifications, webhooks, audit entries, clause extraction, the RabbitMQ externalizer.
When a listener throws, its publication is marked **FAILED**. The backend retries it on its own (issue #81):

- every `EVENTS_RETRY_INTERVAL` (5 min) one replica resubmits FAILED publications older than `EVENTS_RETRY_MIN_AGE`
  (5 min) that had fewer than `EVENTS_RETRY_MAX_ATTEMPTS` (24) attempts — about two hours of retries;
- publications stuck before completion (a pod died mid-way) become FAILED after `EVENTS_STALE_*` (1 h published,
  30 min processing or resubmitted) and are retried the same way;
- on restart every outstanding publication is republished as well.

Alerts: `DiligenceEventsFailing` (a listener has had FAILED publications for 30 min — retries are not fixing it) and
`DiligenceEventsRetriesExhausted` (publications the job gave up on; nothing retries them any more).

## Check

1. Which listener: the `listener` label of the alert, or
   `SELECT listener_id, status, count(*), max(completion_attempts) FROM public.event_publication
   WHERE completion_date IS NULL GROUP BY 1, 2;`
2. Why: backend log for that listener (`kubectl -n diligence logs deploy/diligence-backend --since=1h`), usually a
   dependency: OpenSearch (`SearchIndexer`), SMTP (notifications), a webhook target, RabbitMQ (`externalize`).
3. Fix the dependency first; publications that still have attempts left are retried within the interval.

## Mitigate

- **Retries exhausted, dependency fixed:** give them new attempts; the job picks them up within the interval.

  ```sql
  UPDATE public.event_publication SET completion_attempts = 0
  WHERE status = 'FAILED' AND completion_date IS NULL AND listener_id LIKE '%SearchIndexer%';
  ```

- **Search indexing:** instead of retrying thousands of `SearchIndexer` events, rebuild the index once
  ([opensearch-reindex.md](opensearch-reindex.md)), then mark those publications completed so the alert clears:

  ```sql
  UPDATE public.event_publication SET status = 'COMPLETED', completion_date = now()
  WHERE status = 'FAILED' AND completion_date IS NULL AND listener_id LIKE '%SearchIndexer%';
  ```

- **One event that can never succeed** (a bug, a deleted target): fix or work around the cause, record the event id
  in the incident register, then complete it as above with `AND id = '<id>'`. Never delete rows of the audit listener
  (`AuditEventListener`): those are audit entries that were not written yet.

Document processing (scan, OCR, previews) runs through RabbitMQ, not these events: see
[dead-letter-queue.md](dead-letter-queue.md).
