# Single-Server Production Deployment Guide

Deploy the Diligence Platform on a single Linux VPS using [compose.prod.yaml](../../compose.prod.yaml) ([ADR-0012](adr/0012-single-server-docker-compose-production.md)). For multi-node AWS EKS deployments, see [infrastructure/kubernetes](../infrastructure/kubernetes) and [infrastructure/terraform](../infrastructure/terraform).

## 1. Server Sizing

| Profile | Command | Steady-State RAM | Recommended VPS | Estimated Cost |
|---|---|---|---|---|
| **Default (Ultra-Lean)** | `up -d` | ~2.5–3.5 GiB | **4 vCPU / 8 GiB RAM / 80 GB NVMe** | ~$25–$60/mo |
| **With Local S3** | `--profile local-storage up -d` | ~3.0–4.0 GiB | **4 vCPU / 8 GiB RAM / 120 GB NVMe** | ~$30–$60/mo |
| **With ClamAV + Tika OCR + Gotenberg** | `--profile processing up -d` | ~7.5–9.0 GiB | **8 vCPU / 16 GiB RAM / 120 GB NVMe** | ~$60–$100/mo |

### Feature Trade-offs in Default (Ultra-Lean) Mode

When `CLAMAV_HOST`, `TIKA_URL`, and `GOTENBERG_URL` are left empty:
- **Content-type validation:** Enforced in-process via magic bytes & OOXML ZIP inspection (`EmbeddedContentTypeDetector`).
- **Full-text search indexing:** Works in-process via PDFBox + Apache POI (`EmbeddedTextExtractor`) for digital PDF, DOCX, XLSX, PPTX, TXT, CSV, and EML.
- **In-browser preview:** Works for PDF and images (`PageRasterizer`). Office files fall back to download-only unless `--profile processing` (Gotenberg) is enabled.
- **Scanned PDF / Image OCR & AV scan:** Requires `--profile processing` (Tika + ClamAV), or AWS GuardDuty Malware Protection on S3.

## 2. Prerequisites

1. Ubuntu 24.04 / Debian 12+ with Docker Engine 24+ and Docker Compose v2.
2. Firewall open **only** on TCP `22` (SSH), `80` (ACME HTTP-01), and `443` (HTTPS).
3. DNS `A` (and optionally `AAAA`) records pointing both `APP_DOMAIN` and `AUTH_DOMAIN` to the VPS public IP.
4. S3-compatible bucket (AWS S3 in `ap-southeast-3` or Cloudflare R2) — or skip by enabling `--profile local-storage`.

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

Pin immutable GHCR image tags (`IMAGE_TAG`, `KEYCLOAK_IMAGE_TAG`) to the `sha-<commit>` output from CI on `main`, and configure `APP_DOMAIN`, `AUTH_DOMAIN`, `ACME_EMAIL`, S3 credentials, and SMTP settings in `.env.prod`.

If enabling `--profile processing`, also set in `.env.prod`:

```env
TIKA_URL=http://tika:9998
GOTENBERG_URL=http://gotenberg:3000
CLAMAV_HOST=clamav
```

## 4. Launch & Bootstrap Keycloak

```bash
# Default ultra-lean stack
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

## 5. Automated Daily Backups

Install a daily cron job for PostgreSQL (`diligence` + `keycloak` databases) using [backup-postgres.sh](../infrastructure/docker/scripts/backup-postgres.sh):

```bash
sudo mkdir -p /var/backups/diligence
sudo crontab -e
```

```cron
0 2 * * * /opt/diligence/infrastructure/docker/scripts/backup-postgres.sh >> /var/log/diligence-backup.log 2>&1
```

Set `BACKUP_S3_URI=s3://your-backup-bucket/postgres` in `.env.prod` (with AWS CLI configured on the host) to replicate daily compressed dumps off-server.

## 6. Upgrades & Scale-Up

To release a new version:

1. Update `IMAGE_TAG` (and `KEYCLOAK_IMAGE_TAG` if changed) in `.env.prod`.
2. Pull and recreate containers cleanly:
   ```bash
   docker compose -f compose.prod.yaml --env-file .env.prod pull
   docker compose -f compose.prod.yaml --env-file .env.prod up -d
   ```

When user/document load outgrows a single node, promote without code changes:
- **Step 1:** Move to an 8 vCPU / 16 GiB VPS and add `--profile processing` for ClamAV + Indonesian OCR + Office previews.
- **Step 2:** Provision managed AWS EKS + RDS + ElastiCache + S3 using [infrastructure/terraform](../infrastructure/terraform) and [infrastructure/kubernetes](../infrastructure/kubernetes) ([ADR-0007](adr/0007-container-platform.md), [ADR-0011](adr/0011-lean-architecture-simplification.md)).
