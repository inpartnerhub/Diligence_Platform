# Diligence Platform

Web platform for running company due diligence end to end — request lists, a controlled data room, findings and
risk register, Q&A and reporting — replacing e-mail, spreadsheets and shared folders with one secure workspace for
the consulting team, the client (buy-side) and the target company (sell-side).

The product specification is the PRD *Konsep Produk & Spesifikasi untuk Tim IT — Platform Due Diligence* (v1.0,
30 Oct 2026). Requirement IDs used throughout the code and docs (`PRJ-01`, `DOC-04`, `ADM-02`, …) refer to it;
see [docs/prd](docs/prd/README.md) for the summary and traceability matrix.

> **Status:** MVP sprints 1–14 delivered — foundation, authentication & SSO, MFA, administration and audit log;
> projects, teams, templates and dashboards; data room with folder grants, drag-and-drop/folder upload, versions,
> malware scanning and type validation; watermarked preview, download/print control and full-text search with OCR;
> request list with waves, delivery by the target, Excel import/export, PDF export, in-app and e-mail notifications
> and daily reminders; findings with configurable risk rating, source documents, four-eyes review and partner release;
> Q&A with approval and Word/PDF reports from released findings. Next: hardening, UAT and training (sprint 15–16).

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
│   ├── docker/             Local stack service configs (Keycloak realm, Kong, Prometheus, Grafana, OTel)
│   ├── kubernetes/         Kustomize base + dev/staging/prod overlays
│   └── terraform/          Cloud infrastructure: modules, stack, per-environment roots
├── tests/
│   ├── e2e/                Playwright end-to-end tests
│   └── performance/        k6 load tests
├── docs/
│   ├── prd/                PRD summary and requirement traceability
│   ├── adr/                Architecture Decision Records
│   ├── api/                API guidelines and OpenAPI workflow
│   └── runbooks/           Operational runbooks
├── .github/                CI/CD workflows, Dependabot, CODEOWNERS, templates
├── compose.yaml            Local development stack
├── Makefile                Developer entry points (`make help`)
└── .env.example            Local environment template (copy to .env)
```

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21+ | Gradle provisions a Java 21 toolchain automatically if missing |
| Node.js | 22 LTS+ | see `apps/frontend/.nvmrc` |
| Docker | Engine 24+ with Compose v2 | local stack and integration tests |
| kubectl | 1.30+ | optional, renders Kustomize overlays |
| Terraform | 1.10+ | optional, infrastructure work |

## Quick start

```bash
cp .env.example .env            # replace every "change-me" value (see comments for how to generate secrets)
make up                         # PostgreSQL, Redis, RabbitMQ, SeaweedFS (S3), OpenSearch, Tika, Gotenberg, ClamAV,
                                # Keycloak, Kong, Vault, Mailpit
make keycloak-bootstrap         # admin-api client secret + local admin and PM users from .env
make backend-run                # backend on http://localhost:8080 (second terminal)
cp apps/frontend/.env.example apps/frontend/.env.local   # set SESSION_SECRET and the Redis password
make frontend-install && make frontend-run                # frontend on http://localhost:3000
```

Sign in at http://localhost:3000 with `KEYCLOAK_DEV_ADMIN`. Super admins must enrol an authenticator app (TOTP)
on first login. From **Administration → Users** invite a client or target user; the invitation e-mail (set password,
OTP, NDA) appears in Mailpit at http://localhost:8025.

Or run everything in containers, including observability:

```bash
make up-all && make keycloak-bootstrap
```

### Local endpoints

| Service | URL | Notes |
|---------|-----|-------|
| Frontend | http://localhost:3000 | |
| API via Kong | http://localhost:8000/api/v1 | the path clients use |
| Backend direct | http://localhost:8080 | Swagger UI at `/swagger-ui.html`, spec at `/v3/api-docs` |
| Backend management | http://localhost:8081/actuator | health, Prometheus metrics, module graph |
| Keycloak | http://localhost:8180 | realm `diligence`; admin console on the master realm |
| Mailpit | http://localhost:8025 | every e-mail sent locally |
| RabbitMQ UI | http://localhost:15672 | |
| Object storage (S3 API) | http://localhost:9000 | SeaweedFS; credentials from `.env` |
| OpenSearch | http://localhost:9200 | Dashboards on :5601 (observability profile) |
| Vault (dev mode) | http://localhost:8200 | |
| Grafana | http://localhost:3001 | observability profile |
| Prometheus | http://localhost:9090 | observability profile |

All ports bind to `127.0.0.1` only. If a port clashes with another local service, override `BACKEND_PORT`, `BACKEND_MANAGEMENT_PORT`, `FRONTEND_PORT`, `OBJECT_STORAGE_PORT` or `GRAFANA_PORT` in `.env`.

### Internal SSO (optional)

Set `ENTRA_TENANT_ID` / `ENTRA_CLIENT_ID` / `ENTRA_CLIENT_SECRET` and/or `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`
in `.env` and re-run `make keycloak-bootstrap`. SSO users are provisioned just in time with the least-privileged
role (`OBSERVER`) until an administrator assigns their role.

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
| Kubernetes, secret | HashiCorp Vault → Vault Secrets Operator → Kubernetes Secret |
| CI | GitHub Actions secrets/variables: `SONAR_TOKEN`, `SONAR_HOST_URL`, `SENTRY_AUTH_TOKEN` |

Secrets are never committed. `.env`, `*.tfvars` and `backend.hcl` are git-ignored, and CI runs gitleaks on every push.

## CI/CD

| Workflow | Trigger | Does |
|----------|---------|------|
| `backend.yml` | changes in `apps/backend`, `database` | build, unit, modularity and integration tests, coverage gate, SonarQube, image build + Trivy scan, push on `main` |
| `frontend.yml` | changes in `apps/frontend` | format, lint, typecheck, unit tests, build, SonarQube, image build + Trivy scan, push on `main` |
| `infrastructure.yml` | changes in `infrastructure`, `compose.yaml` | Terraform fmt/validate/tflint, kubeconform on all overlays, compose validation |
| `security.yml` | every PR/push, weekly | gitleaks, dependency review, Trivy filesystem + IaC misconfiguration scan |
| `deploy.yml` | manual | promotes an immutable image tag to dev / staging / prod (GitHub environment protection) |

Images are published to GHCR as `ghcr.io/<owner>/diligence-{backend,frontend}:sha-<git sha>` with SBOM and provenance.
