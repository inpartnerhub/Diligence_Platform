# Search results missing / index lost

Full-text search (DOC-06) uses OpenSearch indices prefixed `OPENSEARCH_INDEX_PREFIX`. PostgreSQL and S3 are the
source of truth; the index can always be rebuilt.

## Check

1. Cluster health (`GET _cluster/health`) and index document count vs. `SELECT count(*) FROM dr.document_version
   WHERE text_extracted`.
2. Ingestion queue backlog ([dead-letter-queue.md](dead-letter-queue.md)) — new uploads are indexed asynchronously.

## Mitigate

- Restore the latest managed OpenSearch snapshot (fastest).
- Otherwise rebuild: there is no bulk re-index endpoint yet. Republish the affected versions to the ingestion queue
  (text extraction and indexing are idempotent per version). Track a bulk re-index job as a follow-up if this is needed
  more than once.
- Search queries always filter by the caller's folders (information barrier); never expose OpenSearch directly.
