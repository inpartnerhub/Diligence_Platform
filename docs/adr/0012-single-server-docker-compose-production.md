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

1. **Single-host runtime with Caddy TLS:** Run `caddy`, `postgres` (shared instance with isolated `diligence` and `keycloak` databases), `redis`, `keycloak` (`--optimized`), `backend` (with `INGESTION_LISTENER_ENABLED=true`, `HTTP_MAX_CONCURRENT_UPLOADS=2`, `DB_POOL_SIZE=10`), and `frontend` on a single **2 vCPU / 4 GiB RAM (+ 2 GiB swap)** starter VPS (~1.6–2.2 GiB steady-state RAM, ~$10–$24/month) or **4 vCPU / 8 GiB RAM** (~2.5–3.5 GiB RAM, ~$25–$60/month via `.env.prod` tuning). Only Caddy exposes ports `80`/`443`; Keycloak `/admin` and `/realms/master` remain blocked externally and accessible only via SSH loopback tunnel to `127.0.0.1:8180` ([ADR-0009](0009-production-keycloak.md)), and the backend management port (`8081`) is bound to loopback `127.0.0.1:8081` only.
2. **Embedded lightweight ingestion & PDF fallback:** When `TIKA_URL` or `GOTENBERG_URL` are left unset in default ultra-lean mode, existing bundled Apache PDFBox and Apache POI libraries provide embedded fallbacks without adding new runtime dependencies:
   - `TikaContentTypeDetector` falls back to `EmbeddedContentTypeDetector` (magic bytes + OLE2/OOXML inspection; spoofed extensions and generic archives are still rejected).
   - `TikaTextExtractor` falls back to `EmbeddedTextExtractor` for full-text search indexing of PDF, `.docx`, `.xlsx`, `.pptx`, `.xls`, ODF (`.odt`, `.ods`, `.odp`), `.txt`, `.csv`, and `.eml`.
   - `PageRasterizer` handles native PDF and image previews, while `EmbeddedPdfRenderer` (via PDFBox + Apache POI) provides in-process PDF generation for **Reports (`RPT-01`/`RPT-02`)**, **Q&A PDF exports (`QNA-03`)**, **Request List PDF exports (`REQ-05`)**, and **modern Office `.docx`/`.xlsx`/`.pptx`/`.txt`/`.csv` previews**.
   - `ClamAvScanner` enforces `CLAMAV_MODE=required` by default (failing closed if `CLAMAV_HOST` is missing); `compose.prod.yaml` explicitly sets `CLAMAV_MODE=external` when `CLAMAV_HOST` is unset, assuming external malware scanning such as AWS S3 GuardDuty Malware Protection.
3. **Opt-in Compose profiles for extra capabilities:**
   - `--profile local-storage` adds self-hosted SeaweedFS S3 when AWS S3 or Cloudflare R2 is not used.
   - `--profile processing` adds `diligence-clamav` (`CLAMAV_MODE=clamav`, `CLAMAV_HOST=clamav`), `diligence-tika` (Tesseract `eng+ind` OCR for scanned PDFs/images and legacy `.doc`/`.ppt`/`.msg` text extraction), and `diligence-gotenberg` (LibreOffice rendering for complex Office fidelity and legacy `.doc`/`.ppt`/`.msg`/ODF to PDF conversion) (~+5 GiB RAM; recommended 8 vCPU / 16 GiB RAM VPS).
   - `--profile observability` adds Prometheus (`prom/prometheus:v3.16.0`, `384m` limit, 14-day retention) with repository alert rules (`alerts.yml`) scraping `backend:8081` and `keycloak:9000` on the internal network, bound to `127.0.0.1:9090`.

## Consequences

### Positive
- Cuts initial production infrastructure cost by >90% (~$10–$24/month on a 2 vCPU / 4 GiB VPS, or ~$25–$60/month on 4 vCPU / 8 GiB vs ~$450–$600/month on EKS).
- Existing bundled PDFBox and Apache POI libraries provide embedded fallbacks without adding new runtime dependencies, while LibreOffice-only layout fidelity, OCR, and local ClamAV remain opt-in; `compose.yaml` (`local` profile) and all Kubernetes overlays keep full ClamAV/Tika/Gotenberg wiring untouched.
- Zero-code upgrade path back to `--profile processing` or multi-node AWS EKS (`infrastructure/terraform` + `infrastructure/kubernetes`) simply by setting environment variables and switching deployment target.

### Security & Operational Trade-offs and Mitigations
- **Shared API + Ingestion process (`HIGH-1`):** Single-server mode runs `INGESTION_LISTENER_ENABLED=true` inside the API JVM (default `1 GiB` container limit / `640 MiB` heap on 4 GiB VPS, or `1.5 GiB` / `1024 MiB` on 8 GiB VPS), so malformed uploads or memory spikes during parsing affect the same JVM as live API traffic. Mitigated by `ZipSecureFile` zip-bomb limits, a 32 MiB OOXML/HSSF cap, `HTTP_MAX_CONCURRENT_UPLOADS=2` (or `4` on 8 GiB), and `-XX:+ExitOnOutOfMemoryError` paired with `restart: unless-stopped`. Upgrade to 8 GiB RAM (`4 vCPU`) when concurrent uploads/reports grow, or migrate to dedicated ingestion workers on AWS EKS ([ADR-0011](0011-lean-architecture-simplification.md)) if handling heavy untrusted upload volume.
- **Fail-closed AV default (`MEDIUM-1`):** The backend **and** `compose.prod.yaml`/`.env.prod.example` all default to `CLAMAV_MODE=required`, which refuses uploads until malware scanning is configured, so the data room never silently accepts unscanned files. Configure one of: `--profile processing` with `CLAMAV_MODE=clamav` + `CLAMAV_HOST=clamav` (self-hosted, 8 GiB preset), or `CLAMAV_MODE=external` **only** when external storage AV (e.g. AWS S3 GuardDuty Malware Protection) is active. This state is surfaced in `/actuator/health` (`avScannerHealthIndicator`) and the Prometheus gauge `diligence_ingestion_av_enabled`.
- **Observability checklist (`MEDIUM-3`):** Single-server production deployments must configure `SENTRY_DSN` for OOM/exception alerting, point an external uptime monitor at `/api/health`, verify loopback management endpoints (`127.0.0.1:8081/actuator/health` and `/actuator/prometheus`), and optionally enable `--profile observability` (`+384 MiB` RAM for Prometheus + alert rules).
- **Single-node availability & feature boundaries:** Single-server deployment is single-AZ / single-node; mitigate data loss via automated off-host PostgreSQL backups ([backup-postgres.sh](../../infrastructure/docker/scripts/backup-postgres.sh)) and durable object storage (AWS S3 or Cloudflare R2). Without `--profile processing`, scanned PDF/image OCR (`eng+ind`), legacy `.doc`/`.ppt`/`.msg` extraction/rendering, ODF-to-PDF rendering, and high-fidelity LibreOffice Office layout remain disabled; enable `--profile processing` on an 8 vCPU / 16 GiB host or upgrade to EKS when workload justifies it.
