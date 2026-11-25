# ADR-0012: Single-Server Production via Docker Compose (Level 3 Early-Scale)

- **Status:** Accepted
- **Date:** 2026-11-25
- **Related:** [ADR-0007](0007-container-platform.md), [ADR-0009](0009-production-keycloak.md), [ADR-0011](0011-lean-architecture-simplification.md)

## Context

Even after consolidating RabbitMQ, OpenSearch, Vault, and Kong into PostgreSQL and AWS managed services ([ADR-0011](0011-lean-architecture-simplification.md)), the Kubernetes/EKS footprint still reserves ~9.6 GiB RAM for application pods plus EKS control plane overhead, cluster add-ons (NGINX Ingress, External Secrets Operator, Kyverno, cert-manager), Managed RDS, Managed Redis, and NAT Gateways (~$450–$600/month), largely driven by:

1. Separate API (`diligence-backend`) and queue worker (`diligence-ingestion`) deployments running identical Spring Boot JARs.
2. Always-on document processing sidecars (`clamd`, Apache Tika Server, LibreOffice/Gotenberg) consuming ~4.6 GiB RAM 24/7 even during low upload volume.
3. Multi-node Kubernetes cluster infrastructure for a single-firm early-scale pilot (< 200 users).

## Decision

Adopt an **ultra-lean single-server Docker Compose production stack** ([compose.prod.yaml](../../compose.prod.yaml)) as the default deployment option for early-stage and pilot production environments, while preserving [infrastructure/kubernetes](../../infrastructure/kubernetes) and [infrastructure/terraform](../../infrastructure/terraform) intact as the multi-node AWS EKS scale-up path:

1. **Single-host runtime with Caddy TLS:** Run `caddy`, `postgres` (shared instance with isolated `diligence` and `keycloak` databases), `redis`, `keycloak` (`--optimized`), `backend` (with `INGESTION_LISTENER_ENABLED=true`), and `frontend` on a single **4 vCPU / 8 GiB RAM** VPS (~2.5–3.5 GiB steady-state RAM, ~$25–$60/month including object storage). Only Caddy exposes ports `80`/`443`; `/admin` and `/realms/master` remain blocked externally and accessible only via SSH loopback tunnel to `127.0.0.1:8180` ([ADR-0009](0009-production-keycloak.md)).
2. **Embedded lightweight ingestion fallback:** When `CLAMAV_HOST`, `TIKA_URL`, or `GOTENBERG_URL` are left unset in production:
   - `TikaContentTypeDetector` falls back to `EmbeddedContentTypeDetector` (magic bytes + ZIP/OOXML entry inspection; spoofed extensions and generic archives are still rejected).
   - `TikaTextExtractor` falls back to `EmbeddedTextExtractor` using Apache PDFBox and Apache POI (already bundled in the backend) for PDF, DOCX, XLSX, PPTX, TXT, CSV, and EML search indexing.
   - `GotenbergPreviewRenderer` skips Office-to-PDF conversion cleanly while native PDF and image previews remain active via `PageRasterizer`.
   - `ClamAvScanner` skips in-process `clamd` scanning so operators can rely on S3 GuardDuty Malware Protection or enable ClamAV on demand.
3. **Opt-in Compose profiles for extra capabilities:**
   - `--profile local-storage` adds self-hosted SeaweedFS S3 when AWS S3 or Cloudflare R2 is not used.
   - `--profile processing` adds `diligence-clamav`, `diligence-tika` (with Tesseract Indonesian OCR), and `diligence-gotenberg` (LibreOffice preview rendering) when scanned-PDF OCR, Office preview rendering, or in-stack virus scanning is required (~+5 GiB RAM; recommended 8 vCPU / 16 GiB RAM VPS).

## Consequences

### Positive
- Cuts initial production infrastructure cost by ~85–90% (~$25–$60/month vs ~$450–$600/month on EKS) and fits comfortably on one 4 vCPU / 8 GiB VPS.
- Zero runtime dependencies removed from application code; `compose.yaml` (`local` profile) and all Kubernetes overlays keep full ClamAV/Tika/Gotenberg wiring untouched.
- Zero-code upgrade path back to `--profile processing` or multi-node AWS EKS (`infrastructure/terraform` + `infrastructure/kubernetes`) simply by setting environment variables and switching deployment target.

### Trade-offs & Mitigations
- Single-server deployment is single-AZ / single-node; mitigate data loss via automated off-host backups ([backup-postgres.sh](../../infrastructure/docker/scripts/backup-postgres.sh)) and storing documents in durable object storage (AWS S3 or Cloudflare R2).
- Without `--profile processing`, scanned image OCR and Office-to-PDF previews are disabled (Office files remain searchable via POI text extraction and downloadable directly); enable `--profile processing` on an 8 vCPU / 16 GiB host or upgrade to EKS when workload justifies it.
