# Search results missing / index lost

Full-text search (DOC-06) uses PostgreSQL native full-text search (`dr.document_search_index`: a generated `tsvector`
over document name and extracted text, GIN index). PostgreSQL (the current CLEAN version of every document) and S3
(the extracted text) are the source of truth; the index can always be rebuilt.

## Check

1. Index row count vs. source of truth:
   `SELECT count(*) FROM dr.document_search_index` against
   `SELECT count(*) FROM dr.document d JOIN dr.document_version v ON v.id = d.current_version_id
   WHERE v.scan_status = 'CLEAN'`.
2. Ingestion queue backlog ([ingestion-queue.md](ingestion-queue.md)) — new uploads are scanned, extracted and
   indexed asynchronously, so a backlog shows as recently-uploaded documents missing from search.
3. Failed indexing events (`SearchIndexer` in `DiligenceEventsFailing`): retried automatically
   ([failed-events.md](failed-events.md)).

## Mitigate

Rebuild the index from the database (issue #80):

1. Sign in as Super Admin (password + MFA) and open **Administration → Search index**. Select **Rebuild index**.
   Without the web app: `POST /api/v1/admin/search/reindex` with a Super Admin token signed in within the step-up
   window; `GET` on the same path shows progress.
2. The run indexes the current CLEAN version of every document from PostgreSQL and the text the pipeline already
   stored in S3 (`…/text.txt`). Nothing is scanned, extracted or rendered again, and search keeps working meanwhile.
3. Afterwards it removes the entries it did not refresh (documents deleted or no longer CLEAN in the database).
   Entries indexed by uploads during the run are kept. When documents failed (S3 read errors, see the backend
   log `Re-index:`) the removal is skipped and the page says so: fix the cause and run it again.
4. Check: index row count vs. `SELECT count(*) FROM dr.document d JOIN dr.document_version v
   ON v.id = d.current_version_id WHERE v.scan_status = 'CLEAN'`.

Notes:

- One run at a time across replicas. A run whose pod died stops reporting progress; after
  `SEARCH_REINDEX_STALE_AFTER` (10 min) the next start marks it failed and takes over.
- Throughput is bounded by S3 reads (`SEARCH_REINDEX_PARALLELISM`, 16) and bulk writes
  (`SEARCH_REINDEX_BATCH_SIZE`, `SEARCH_REINDEX_BULK_MAX_SIZE`). Record its duration in restore tests: it is part
  of the RTO.
- The index lives in the same PostgreSQL database as the source rows, so a PostgreSQL point-in-time restore brings it
  back consistently; re-index only when the index and the base tables have drifted.
- Documents whose text was never extracted (`text_extracted = false`) are indexed by name only. To redo extraction,
  requeue their versions ([ingestion-queue.md](ingestion-queue.md)).
- Search queries always run under row-level security and filter by the caller's folders (information barrier); the
  index is never queried outside the caller's RLS-enforced transaction.
