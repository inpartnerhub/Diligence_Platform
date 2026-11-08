# Search results missing / index lost

Full-text search (DOC-06) uses OpenSearch indices prefixed `OPENSEARCH_INDEX_PREFIX`. PostgreSQL and S3 are the
source of truth; the index can always be rebuilt.

## Check

1. Cluster health (`GET _cluster/health`) and index document count vs. `SELECT count(*) FROM dr.document_version
   WHERE text_extracted`.
2. Ingestion queue backlog ([dead-letter-queue.md](dead-letter-queue.md)) — new uploads are indexed asynchronously.

## Mitigate

Rebuild the index from the database (issue #80):

1. Sign in as Super Admin (password + MFA) and open **Administration → Search index**. Select **Rebuild index**.
   Without the web app: `POST /api/v1/admin/search/reindex` with a Super Admin token signed in within the step-up
   window; `GET` on the same path shows progress.
2. The run indexes the current CLEAN version of every document from PostgreSQL and the text the pipeline already
   stored in S3 (`…/text.txt`). Nothing is scanned, extracted or rendered again, and search keeps working meanwhile.
3. Afterwards it removes the entries it did not refresh (documents deleted or no longer CLEAN in the database).
   Entries indexed by uploads during the run are kept. When documents failed (S3 or OpenSearch errors, see the backend
   log `Re-index:`) the removal is skipped and the page says so: fix the cause and run it again.
4. Check: index document count vs. `SELECT count(*) FROM dr.document d JOIN dr.document_version v
   ON v.id = d.current_version_id WHERE v.scan_status = 'CLEAN'`.

Notes:

- One run at a time across replicas. A run whose pod died stops reporting progress; after
  `SEARCH_REINDEX_STALE_AFTER` (10 min) the next start marks it failed and takes over.
- Throughput is bounded by S3 reads (`SEARCH_REINDEX_PARALLELISM`, 16) and bulk requests
  (`SEARCH_REINDEX_BATCH_SIZE`, `SEARCH_REINDEX_BULK_MAX_SIZE`). Record its duration in restore tests: it is part
  of the RTO.
- A managed OpenSearch snapshot is faster only when it matches the database's point in time; after a PostgreSQL
  point-in-time restore it does not, so re-index instead (or after restoring the snapshot).
- Documents whose text was never extracted (`text_extracted = false`) are indexed by name only. To redo extraction,
  republish their versions to the ingestion queue ([dead-letter-queue.md](dead-letter-queue.md)).
- Search queries always filter by the caller's folders (information barrier); never expose OpenSearch directly.
