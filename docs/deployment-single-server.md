# Single-Server Production Deployment Guide

Deploy the Diligence Platform on a single Linux VPS using [compose.prod.yaml](../compose.prod.yaml) ([ADR-0012](adr/0012-single-server-docker-compose-production.md)). For multi-node AWS EKS deployments, see [infrastructure/kubernetes](../infrastructure/kubernetes) and [infrastructure/terraform](../infrastructure/terraform).

## 1. Server Sizing

| Profile | Command | Steady-State RAM | Recommended VPS | Estimated Cost |
|---|---|---|---|---|
| **Default (Ultra-Lean)** | `up -d` | ~2.5–3.5 GiB | **4 vCPU / 8 GiB RAM / 80 GB NVMe** | ~$25–$60/mo |
| **With Local S3** | `--profile local-storage up -d` | ~3.0–4.0 GiB | **4 vCPU / 8 GiB RAM / 120 GB NVMe** | ~$30–$60/mo |
| **With Prometheus** | `--profile observability up -d` | ~2.9–3.9 GiB (+384 MiB) | **4 vCPU / 8 GiB RAM / 80 GB NVMe** | ~$25–$60/mo |
| **With ClamAV + Tika OCR + Gotenberg** | `--profile processing up -d` | ~7.5–9.0 GiB | **8 vCPU / 16 GiB RAM / 120 GB NVMe** | ~$60–$100/mo |

### Capability Matrix: Ultra-Lean Default vs. `--profile processing`

**Working in default ultra-lean mode (without `--profile processing`):**
- **Content-type validation:** Enforced in-process via magic bytes and OLE2/OOXML inspection (`EmbeddedContentTypeDetector`).
- **Full-text search indexing:** Enforced in-process via PDFBox + Apache POI (`EmbeddedTextExtractor`) for PDF, `.docx`, `.xlsx`, `.pptx`, `.xls`, ODF (`.odt`, `.ods`, `.odp`), `.txt`, `.csv`, and `.eml`.
- **Previews & in-process PDF generation:** Native PDF and image previews (`PageRasterizer`), plus in-process PDF generation (`EmbeddedPdfRenderer` via PDFBox + Apache POI) for **Reports (`RPT-01`/`RPT-02`)**, **Q&A PDF exports (`QNA-03`)**, **Request List PDF exports (`REQ-05`)**, and **modern Office `.docx`/`.xlsx`/`.pptx`/`.txt`/`.csv` previews**.

**Requires `--profile processing` (or external S3 AV):**
- **Complex Office fidelity & legacy rendering:** LibreOffice/Gotenberg rendering for complex Office layouts and legacy `.doc`/`.ppt`/`.msg`/ODF to PDF conversion.
- **OCR & legacy text extraction:** Scanned PDF / image Tesseract OCR (`eng+ind`) and legacy `.doc`/`.ppt`/`.msg` text extraction via Apache Tika.
- **Self-hosted malware scanning:** In-stack ClamAV scanning (`CLAMAV_MODE=clamav`, `CLAMAV_HOST=clamav`), if not relying on external storage AV such as AWS S3 GuardDuty Malware Protection (`CLAMAV_MODE=external`).

### Security & Operational Trade-offs

- **Shared API + Ingestion process:** Single-server mode runs `INGESTION_LISTENER_ENABLED=true` inside the API JVM (`1.5 GiB` container memory limit / `1024 MiB` heap). Heavy or malformed uploads share memory with API requests. This is mitigated by `ZipSecureFile` zip-bomb limits, a 32 MiB OOXML/HSSF cap, `HTTP_MAX_CONCURRENT_UPLOADS=4`, and `-XX:+ExitOnOutOfMemoryError` + `restart: unless-stopped`. If you handle high volumes of untrusted uploads, migrate to dedicated ingestion workers on AWS EKS ([ADR-0011](adr/0011-lean-architecture-simplification.md)).
- **Fail-closed AV default:** The backend defaults to `CLAMAV_MODE=required` and refuses uploads when `CLAMAV_HOST` is blank. `compose.prod.yaml` explicitly sets `CLAMAV_MODE=external` assuming AWS S3 GuardDuty Malware Protection (or an equivalent external scanner) is enabled on the bucket. Always verify `avScannerHealthIndicator` in `/actuator/health` and the `diligence_ingestion_av_enabled` gauge in `/actuator/prometheus`.

## 2. Prerequisites

1. Ubuntu 24.04 / Debian 12+ with Docker Engine 24+ and Docker Compose v2.
2. Firewall open **only** on TCP `22` (SSH), `80` (ACME HTTP-01), and `443` (HTTPS).
3. DNS `A` (and optionally `AAAA`) records pointing both `APP_DOMAIN` and `AUTH_DOMAIN` to the VPS public IP.
4. S3-compatible bucket (AWS S3 in `ap-southeast-3` with GuardDuty Malware Protection, or Cloudflare R2) — or skip by enabling `--profile local-storage`.

## 3. Initial Setup

```bash
git clone https://github.com/inpartnerhub/Diligence_Platform.git /opt/diligence
cd /opt/diligence

cp .env.prod.example .env.prod
chmod 600 .env.prod
```

Generate strong random secrets and fill `.env.prod`:

```bash
openssl rand -hex 32     # KEYCLOAK_ADMIN_CLIENT_SECRET, OIDC_CLIENT_SECRET
openssl rand -base64 48  # SESSION_SECRET
openssl rand -base64 32  # SESSION_ENCRYPTION_KEY, WEBHOOK_KEY_ENCRYPTION_KEY, DB/Redis passwords
```

Pin immutable GHCR image tags (`IMAGE_TAG`, `KEYCLOAK_IMAGE_TAG`) to the `sha-<commit>` output from CI on `main`, and configure `APP_DOMAIN`, `AUTH_DOMAIN`, `ACME_EMAIL`, S3 credentials, `SMTP_*`, and `SENTRY_DSN` in `.env.prod`. Keep `CLAMAV_MODE=external` only when external/S3 malware scanning is active; leaving `CLAMAV_MODE=required` with blank `CLAMAV_HOST` fails closed on uploads. Keep `HTTP_MAX_CONCURRENT_UPLOADS=4` to bound in-process ingestion memory.

If enabling `--profile processing`, also set in `.env.prod`:

```env
TIKA_URL=http://tika:9998
GOTENBERG_URL=http://gotenberg:3000
CLAMAV_MODE=clamav
CLAMAV_HOST=clamav
```

## 4. Launch & Bootstrap Keycloak

```bash
# Default ultra-lean stack (add --profile observability to include Prometheus)
docker compose -f compose.prod.yaml --env-file .env.prod pull
docker compose -f compose.prod.yaml --env-file .env.prod up -d

# Check container health
docker compose -f compose.prod.yaml --env-file .env.prod ps
```

Wait for `keycloak`, `backend`, `frontend`, and `caddy` to become `healthy`. On first boot:

1. Access Keycloak Admin Console securely via SSH tunnel from your workstation (public `/admin` returns `404` on `AUTH_DOMAIN`):
   ```bash
   ssh -L 8180:127.0.0.1:8180 user@your-vps-ip
   ```
   Open `http://127.0.0.1:8180/admin` locally and sign in with `KEYCLOAK_ADMIN_USERNAME` / `KEYCLOAK_ADMIN_PASSWORD`.
2. In the `diligence` realm:
   - Set client `diligence-admin-api` secret to `KEYCLOAK_ADMIN_CLIENT_SECRET`.
   - Set client `diligence-web` secret to `OIDC_CLIENT_SECRET`, and ensure Valid Redirect URIs include `https://<APP_DOMAIN>/auth/callback` and Web Origins include `https://<APP_DOMAIN>`.
   - Configure SMTP settings in Realm Settings → Email if Keycloak sends verification/reset e-mails directly.
   - Create the initial `SUPER_ADMIN` user (OTP enrollment is enforced on first login).

## 5. Observability & Health Checklist

Single-server deployments do not run a separate Kubernetes monitoring stack by default, so complete this checklist before going live:

1. **Error & OOM alerting (`SENTRY_DSN`):** Set `SENTRY_DSN` in `.env.prod` so unhandled backend/frontend exceptions and OOM-induced restarts are captured immediately.
2. **External uptime monitor:** Configure an external HTTP check against `https://<APP_DOMAIN>/api/health` (for example UptimeRobot, Better Stack, or Checkly) with alerting on non-200 responses or TLS expiry.
3. **Loopback management endpoints:** `compose.prod.yaml` binds the backend management port to `127.0.0.1:8081` only. On the VPS (or over `ssh -L 8081:127.0.0.1:8081 user@your-vps-ip`), inspect:
   - `curl -fsS http://127.0.0.1:8081/actuator/health` — verify database, Redis, S3, and `avScannerHealthIndicator` status.
   - `curl -fsS http://127.0.0.1:8081/actuator/prometheus | grep diligence_ingestion_av_enabled` — verify the AV scanner metric reflects your configured `CLAMAV_MODE`.
4. **Optional in-stack Prometheus (`--profile observability`):** Adds Prometheus (`+384 MiB` RAM, 14-day retention) with repository alert rules (`infrastructure/docker/prometheus/alerts.yml`) scraping `backend:8081` and `keycloak:9000`:
   ```bash
   docker compose -f compose.prod.yaml --env-file .env.prod --profile observability up -d
   ssh -L 9090:127.0.0.1:9090 user@your-vps-ip
   ```
   Then open `http://127.0.0.1:9090` locally to inspect targets and active alerts.

## 6. Automated Daily Backups

Install a daily cron job for PostgreSQL (`diligence` + `keycloak` databases) using [backup-postgres.sh](../infrastructure/docker/scripts/backup-postgres.sh):

```bash
sudo mkdir -p /var/backups/diligence
sudo crontab -e
```

```cron
0 2 * * * /opt/diligence/infrastructure/docker/scripts/backup-postgres.sh >> /var/log/diligence-backup.log 2>&1
```

Set `BACKUP_S3_URI=s3://your-backup-bucket/postgres` in `.env.prod` (with AWS CLI configured on the host) to replicate daily compressed dumps off-server.

## 7. Upgrades & Scale-Up

To release a new version:

1. Update `IMAGE_TAG` (and `KEYCLOAK_IMAGE_TAG` if changed) in `.env.prod`.
2. Pull and recreate containers cleanly:
   ```bash
   docker compose -f compose.prod.yaml --env-file .env.prod pull
   docker compose -f compose.prod.yaml --env-file .env.prod up -d
   ```

When user/document load or untrusted upload risk outgrows a single node, promote without code changes:
- **Step 1:** Move to an 8 vCPU / 16 GiB VPS and add `--profile processing` (`CLAMAV_MODE=clamav`, `CLAMAV_HOST=clamav`) for ClamAV + Indonesian OCR + full LibreOffice Office rendering.
- **Step 2:** Provision managed AWS EKS + RDS + ElastiCache + S3 with dedicated ingestion worker pods using [infrastructure/terraform](../infrastructure/terraform) and [infrastructure/kubernetes](../infrastructure/kubernetes) ([ADR-0007](adr/0007-container-platform.md), [ADR-0011](adr/0011-lean-architecture-simplification.md)).
