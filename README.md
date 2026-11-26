# Diligence Platform

Web platform for running company due diligence end to end — request lists, a controlled data room, findings and
risk register, Q&A and reporting — replacing e-mail, spreadsheets and shared folders with one secure workspace for
the consulting team, the client (buy-side) and the target company (sell-side).

The product specification is the PRD *Konsep Produk & Spesifikasi untuk Tim IT — Platform Due Diligence* (v1.0,
30 Oct 2026). Requirement IDs used throughout the code and docs (`PRJ-01`, `DOC-04`, `ADM-02`, …) refer to it;
see [docs/prd](docs/prd/README.md) for the summary and traceability matrix.

> **Status:** MVP sprints 1–16 technical scope and Phase 2/3 capabilities delivered — foundation, authentication & SSO,
> MFA for all roles, administration and immutable audit log; projects, teams, templates, project & portfolio
> dashboards, and time tracking; data room with folder grants, drag-and-drop/folder upload, versions, ClamAV malware
> scanning, Tika type validation/OCR, Gotenberg previews, dynamic watermark, download/print control, annotations,
> duplicate detection, and rule-based key-clause extraction; request list with waves, target delivery, Excel
> import/export, PDF export, notifications, and daily reminders; findings with configurable risk matrix, source
> citations, four-eyes review, partner release, remediation tracking, and finding library; Q&A with approval; Word/PDF
> reports with tiered approval and PPTX/XLSX exports; retention/purge, outbound webhooks, and the lean AWS/PostgreSQL
> architecture ([ADR-0011](docs/adr/0011-lean-architecture-simplification.md)). Remaining go-live gates: business-run
> external penetration test, UAT sign-off, and first backup/restore test.

- Architecture: [ARCHITECTURE.md](ARCHITECTURE.md)
- Security policy: [SECURITY.md](SECURITY.md)
- Contribution workflow: [CONTRIBUTING.md](CONTRIBUTING.md)

## Repository layout

```
.
├── apps/
│   ├── backend/            Spring Boot 4 modular monolith (Java 21, Gradle Kotlin DSL)
│   └── frontend/           Next.js 16 / React 19 / TypeScript / Ant Design 6, BFF auth, next-intl (id/en)
├── database/
│   ├── migrations/         Flyway migrations (one schema per module, RLS policies)
│   └── seeds/              Local-only seed data (PRD Appendix B request-list template)
├── infrastructure/
│   ├── docker/             Local stack & custom images (Keycloak image & realm, Prometheus, Grafana, Tika, Gotenberg, ClamAV)
│   ├── kubernetes/         Kustomize base + dev/staging/prod overlays, Keycloak, and processing namespaces
│   └── terraform/          Cloud infrastructure: modules, stack, per-environment roots
├── tests/
│   ├── e2e/                Playwright end-to-end tests
│   ├── performance/        k6 load tests
│   └── security/           OWASP ZAP DAST baseline and API scan configs
├── docs/
│   ├── prd/                PRD summary and requirement traceability
│   ├── adr/                Architecture Decision Records
│   ├── api/                API guidelines and OpenAPI workflow
│   ├── guides/             User guides per role and the training plan
│   ├── runbooks/           Operational runbooks
│   ├── security/           Penetration test scope and findings, client addresses
│   ├── uat/                User acceptance test plan and scenarios
│   └── deployment-single-server.md  Single-server production guide (compose.prod.yaml)
├── .github/                CI/CD workflows, Dependabot, CODEOWNERS, templates
├── compose.yaml            Local development stack
├── compose.prod.yaml       Single-server production stack (Level 3 / ADR-0012, 4 vCPU / 8 GiB)
├── Makefile                Developer entry points (`make help`)
├── .env.example            Local environment template (copy to .env)
└── .env.prod.example       Single-server production environment template (copy to .env.prod)
```

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21+ | Gradle provisions a Java 21 toolchain automatically if missing |
| Node.js | 22 LTS+ | see `apps/frontend/.nvmrc` |
| Docker | Engine 24+ with Compose v2 | local stack and integration tests |
| kubectl | 1.30+ | optional, renders Kustomize overlays |
| Terraform | 1.10+ | optional, infrastructure work |

### Host & Virtual Machine Resource Requirements

Running the simplified Diligence container stack locally requires modest memory allocation for the Docker Desktop / Colima / OrbStack Linux virtual machine (see [ADR-0011](docs/adr/0011-lean-architecture-simplification.md)):

| Stack Profile | Minimum Allocated RAM | Recommended RAM | Minimum CPUs | Notes |
|---------------|-----------------------|-----------------|--------------|-------|
| **Core Dev** (`make up` + local apps) | **3.5 GiB (~3.8 GB)** | **4.0+ GiB (~4.3+ GB)** | 4 cores | Infrastructure containers only; JVM backend and Next.js frontend run directly on host |
| **Full Containerized** (`make up-all`) | **5.0 GiB (~5.4 GB)** | **6.0+ GiB (~6.4+ GB)** | 4+ cores | Adds containerized backend, frontend, Prometheus, and Grafana |

#### Empirically Measured Container Resource Specifications

Measured directly via `docker stats` during active runtime, document extraction (Tika), and PDF rendering (Gotenberg):

| Service | Image | Idle RAM (MB / GB) | Active & Peak RAM (MB / GB) | CPU (Idle / Load) | Notes |
|---------|-------|--------------------|-----------------------------|-------------------|-------|
| `backend` | `diligence-backend:local` | ~815–840 MB (0.84 GB) | **~875–895 MB (0.90 GB)** | 0.5% / 15–25% | Spring Boot 4 modular monolith JVM |
| `frontend` | `diligence-frontend:local` | ~79–84 MB (0.08 GB) | **~100 MB (0.10 GB)** | 0.0% / 1–5% | Next.js 16 standalone server & BFF session handler |
| `keycloak` | `quay.io/keycloak/keycloak:26.8` | ~470 MB (0.47 GB) | **~498 MB (0.50 GB)** | 0.7% / 5–10% | Quarkus runtime, realm auth & token issuance |
| `clamav` | `clamav/clamav-debian:1.5` | ~333–438 MB (0.44 GB) | **~1,028 MB (1.03 GB)** | 0.0% / 5–15% | Peaks during signature update and active upload scans |
| `gotenberg` | `gotenberg/gotenberg:8` | ~13–19 MB (0.02 GB) | **~472 MB (0.47 GB)** | 0.0% / 10–30% | Headless Chromium process allocates ~472 MB on active rendition |
| `tika` | `apache/tika:4.2.0-1-full` | ~65–68 MB (0.07 GB) | **~372 MB (0.37 GB)** | 0.2% / 10–20% | Java JVM jumps during active full-text / OCR extraction |
| `object-storage` | `chrislusf/seaweedfs:4.53` | ~68–83 MB (0.08 GB) | **~84 MB (0.08 GB)** | 0.4% / 2–5% | SeaweedFS S3 gateway |
| `postgres` | `postgres:17-alpine` | ~50–60 MB (0.06 GB) | **~63 MB (0.06 GB)** | 0.0% / 2–5% | PostgreSQL 17 with RLS, FTS, and transactional job queue |
| `mailpit` | `axllent/mailpit:v1.33` | ~16–25 MB (0.03 GB) | **~26 MB (0.03 GB)** | 0.0% / 0.5% | Local SMTP and web UI |
| `redis` | `redis:8-alpine` | ~8–14 MB (0.01 GB) | **~16 MB (0.02 GB)** | 0.2% / 1–2% | Session and rate-limit cache |
| `prometheus` *(opt)* | `prom/prometheus:v3.16.0` | ~126 MB (0.13 GB) | **~157 MB (0.16 GB)** | 0.2% / 1–2% | Observability profile |
| `grafana` *(opt)* | `grafana/grafana:13.3.0` | ~105 MB (0.11 GB) | **~126 MB (0.13 GB)** | 0.0% / 1–2% | Observability profile |
| **Total Stack** | — | **~1.9–2.1 GB** | **~3.0–3.6 GB** | — | Peaks up to ~3.9 GB with observability; allocate ≥ 5.4–6.4 GB (5–6 GiB) VM RAM to prevent OOM (`Exit 137`) |

> **Note:** On macOS hosts with default 4.0 GiB (4.3 GB) Docker Desktop VM memory, `make up` runs comfortably alongside host-run backend and frontend processes. If running `make up-all` or high-throughput bulk ingestion alongside `clamd`, allocate **≥ 6.0 GiB (~6.4 GB)** (and Swap: ≥ 2 GB) via **Docker Desktop Settings → Resources → Advanced** to avoid OOM kills (`Exit 137`).

## Quick start

```bash
cp .env.example .env            # replace every "change-me" value (see comments for how to generate secrets)
make up                         # PostgreSQL (FTS + ingestion queue), Redis, SeaweedFS (S3), Tika, Gotenberg,
                                # ClamAV, Keycloak, Mailpit
make keycloak-bootstrap         # admin-api client secret + local admin and PM users from .env
make backend-run                # backend on http://localhost:8080 (second terminal)
cp apps/frontend/.env.example apps/frontend/.env.local   # set SESSION_SECRET, SESSION_ENCRYPTION_KEY, OIDC_CLIENT_SECRET, Redis password
make frontend-install && make frontend-run                # frontend on http://localhost:3000
```

Sign in at http://localhost:3000 with `KEYCLOAK_DEV_ADMIN`. Every user must enrol an authenticator app (TOTP) on first
login (#161); the local PM uses `KEYCLOAK_DEV_TOTP_SECRET` when it is set. After a realm change run
`make keycloak-reset`. From **Administration → Users** invite a client or target user; the invitation e-mail (set password,
OTP, NDA) appears in Mailpit at http://localhost:8025.

Keycloak keeps its users in the `diligence-keycloak-data` volume, so they survive `make down`. The realm is imported
from `infrastructure/docker/keycloak/diligence-realm.json` only when it does not exist yet: after changing that file,
run `make keycloak-reset` (PostgreSQL must be running). The application uses the Keycloak user id as its account id;
`make keycloak-bootstrap` therefore creates users with the id the application already has for their e-mail, and
existing projects stay visible. Only the users from `.env` are recreated: a reset loses users invited through the app or
provisioned by SSO, and their application accounts then block a new invitation for the same e-mail. Avoid
`make keycloak-reset` while you need them, or start over with `make clean-volumes`.

Or run everything in containers, including observability:

```bash
make up-all && make keycloak-bootstrap
```

### Local endpoints

| Service | URL | Notes |
|---------|-----|-------|
| Frontend | http://localhost:3000 | Calls the backend directly over `API_BASE_URL` |
| Backend direct | http://localhost:8080 | Swagger UI at `/swagger-ui.html`, spec at `/v3/api-docs` |
| Backend management | http://localhost:8081/actuator | health, Prometheus metrics, module graph |
| Keycloak | http://localhost:8180 | realm `diligence`; admin console on the master realm |
| Mailpit | http://localhost:8025 | every e-mail sent locally |
| Object storage (S3 API) | http://localhost:9000 | SeaweedFS; credentials from `.env` |
| Grafana | http://localhost:3001 | observability profile |
| Prometheus | http://localhost:9090 | observability profile |

All ports bind to `127.0.0.1` only. If a port clashes with another local service, override `BACKEND_PORT`, `BACKEND_MANAGEMENT_PORT`, `FRONTEND_PORT`, `OBJECT_STORAGE_PORT` or `GRAFANA_PORT` in `.env`.

### Internal SSO (optional)

Set `ENTRA_TENANT_ID` / `ENTRA_CLIENT_ID` / `ENTRA_CLIENT_SECRET` and/or `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` /
`GOOGLE_HOSTED_DOMAIN` in `.env` and re-run `make keycloak-bootstrap`. The tenant id and the hosted domain are
required, so only the firm's own accounts can sign in. An SSO user's first sign-in creates the account (listed in
User administration) without a role; the web app and the API refuse it until a Super Admin assigns one.

## Development

| Task | Command |
|------|---------|
| Backend build, unit + module-boundary tests, coverage gate (≥ 70% business logic) | `make backend-test` |
| Backend integration tests (Testcontainers: Flyway, RLS barriers, audit immutability) | `make backend-it` |
| Frontend format, lint, typecheck, unit tests, build | `make frontend-check` |
| Regenerate frontend API types from OpenAPI | `make api-types` (backend must be running) |
| Everything CI runs without Docker | `make verify` |
| Render Kubernetes manifests | `kubectl kustomize infrastructure/kubernetes/overlays/dev` |

New database changes go in `database/migrations/postgresql` — see [database/README.md](database/README.md).
New backend features go inside their PRD module — see [ARCHITECTURE.md](ARCHITECTURE.md#3-backend-modular-monolith).

## Configuration

Every environment-specific value is an environment variable (12-factor).

| Scope | Source |
|-------|--------|
| Local (compose / host) | `.env` from `.env.example`, `apps/frontend/.env.local` from `apps/frontend/.env.example` |
| Kubernetes, non-secret | ConfigMaps in `infrastructure/kubernetes` overlays |
| Kubernetes, secret | AWS Secrets Manager → External Secrets Operator → Kubernetes Secret |
| CI | GitHub Actions secrets/variables: `SENTRY_AUTH_TOKEN` |

Secrets are never committed. `.env`, `*.tfvars` and `backend.hcl` are git-ignored, and CI runs gitleaks on every push.

## CI/CD

| Workflow | Trigger | Does |
|----------|---------|------|
| `backend.yml` | changes in `apps/backend`, `database` | build, unit, modularity and integration tests, coverage gate, image build + Trivy scan, cosign sign + push on `main` |
| `frontend.yml` | changes in `apps/frontend` | format, lint, typecheck, unit tests, build, image build + Trivy scan, cosign sign + push on `main` |
| `infrastructure.yml` | changes in `infrastructure`, `compose.yaml` | Terraform fmt/validate/tflint, kubeconform on all overlays, image pin & NetworkPolicy checks, compose & alert-rules validation |
| `processing-images.yml` | changes in `infrastructure/docker/{clamav,gotenberg,tika,keycloak}` | rebuilds ClamAV, Gotenberg, Indonesian-OCR Tika, and production Keycloak images + Trivy scan, cosign sign + push on `main` |
| `container-image.yml` | called by `backend`, `frontend`, `processing-images` | reusable Docker build, Trivy scan, GHCR push, SBOM, provenance, and keyless `cosign` signing |
| `codeql.yml` | every PR/push to `main`, weekly | CodeQL SAST for Java, TypeScript, and GitHub Actions workflows |
| `security.yml` | every PR/push, weekly | gitleaks, pinned-actions check, dependency review, Trivy filesystem + IaC misconfiguration scan |
| `dast.yml` | weekly (Sunday), manual | OWASP ZAP baseline + API scan against the full compose stack |
| `performance.yml` | manual | k6 load or upload stress tests against `dev` or `staging` |
| `deploy.yml` | manual | promotes an immutable image tag to dev / staging / prod (GitHub environment protection) |

Images are published to GHCR as `ghcr.io/<owner>/diligence-{backend,frontend}:sha-<git sha>` with SBOM and provenance.
