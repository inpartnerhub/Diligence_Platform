# Document processing stuck / dead-letter queue

**Alert:** `DiligenceMessagesStuck`. Uploads are processed asynchronously: malware scan (ClamAV) → type detection and
text/OCR (Tika + Tesseract) → preview (Gotenberg/PDFBox) → search index (DOC-02, DOC-03, DOC-06).

## Check

1. RabbitMQ management UI (`:15672` locally, port-forward in Kubernetes): queue
   `diligence.ingestion.document-uploaded` — ready vs. unacked messages, consumer count.
2. Failed messages are routed via exchange `diligence.dlx` to queues ending in `.dlq` after 3 attempts
   (`spring.rabbitmq.listener.simple.retry`). Open a message to see the document/version id and the error header.
3. Backend logs for `IngestionPipeline` errors; health of ClamAV, Tika and Gotenberg.

## Mitigate

- Dependency down (ClamAV updating signatures, Tika OOM): restart it; consumers resume automatically.
- Replay after the fix: in the RabbitMQ UI use *Move messages* (shovel) from the `.dlq` queue back to
  `diligence.ingestion.document-uploaded`. Processing is idempotent per version.
- A single poison file (corrupt PDF, encrypted archive): leave it in the DLQ, tell the uploader; the document stays
  unavailable because nothing is served before it is scanned CLEAN.
