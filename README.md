# Platform

Enterprise platform monorepo: a **Next.js + Ant Design** web frontend and a **Spring Boot modular monolith** backend,
with document storage and processing (S3, Tika, Tesseract OCR, ClamAV), search (OpenSearch), messaging
(RabbitMQ), Keycloak/OIDC security behind the Kong gateway, and full observability.

> **Status:** project skeleton. Integration boundaries, configuration and delivery pipeline are in place; business
> features are added incrementally inside the existing modules.

- Architecture: [ARCHITECTURE.md](ARCHITECTURE.md)
- Security policy: [SECURITY.md](SECURITY.md)
- Contribution workflow: [CONTRIBUTING.md](CONTRIBUTING.md)

## Repository layout

```
.
├── apps/
│   ├── backend/            Spring Boot 4 modular monolith (Java 21, Gradle Kotlin DSL)
│   └── frontend/           Next.js 16 / React 19 / TypeScript / Ant Design 6
├── database/
│   ├── migrations/         Flyway migrations (packaged into the backend jar)
│   └── seeds/              Local-only seed data
├── infrastructure/
│   ├── docker/             Local stack service configs (Keycloak, Kong, Prometheus, Grafana, OTel)
│   ├── kubernetes/         Kustomize base + dev/staging/prod overlays
│   └── terraform/          Cloud infrastructure: modules, stack, per-environment roots
├── tests/
│   ├── e2e/                Playwright end-to-end tests
│   └── performance/        k6 load tests
├── docs/
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
cp .env.example .env            # then replace every "change-me" value
make up                         # PostgreSQL, Redis, RabbitMQ, MinIO, OpenSearch, Tika, ClamAV, Keycloak, Kong, Vault
make dev-user                   # create a Keycloak developer account from .env
make backend-run                # backend on http://localhost:8080 (in a second terminal)
make frontend-install && make frontend-run   # frontend on http://localhost:3000
```

Or run everything in containers, including observability:

```bash
make up-all
```

### Local endpoints

| Service | URL | Notes |
|---------|-----|-------|
| Frontend | http://localhost:3000 | |
| API via Kong | http://localhost:8000/api/v1 | the path clients use |
| Backend direct | http://localhost:8080 | Swagger UI at `/swagger-ui.html`, spec at `/v3/api-docs` |
| Backend management | http://localhost:8081/actuator | health, Prometheus metrics, module graph |
| Keycloak | http://localhost:8180 | realm `platform` |
| RabbitMQ UI | http://localhost:15672 | |
| MinIO console | http://localhost:9001 | |
| OpenSearch | http://localhost:9200 | Dashboards on :5601 (observability profile) |
| Vault (dev mode) | http://localhost:8200 | |
| Grafana | http://localhost:3001 | observability profile |
| Prometheus | http://localhost:9090 | observability profile |

All ports bind to `127.0.0.1` only.

### Calling the API

```bash
# Obtain a token through the browser login flow in the frontend, or for local testing temporarily enable
# "Direct access grants" on the platform-frontend client in Keycloak. Never enable it outside local.
curl -H "Authorization: Bearer $TOKEN" http://localhost:8000/api/v1/me
```

## Development

| Task | Command |
|------|---------|
| Backend build + unit + module-boundary tests | `make backend-test` |
| Backend integration tests (Testcontainers) | `make backend-it` |
| Frontend format, lint, typecheck, build | `make frontend-check` |
| Regenerate frontend API types from OpenAPI | `make api-types` (backend must be running) |
| Everything CI runs without Docker | `make verify` |
| Render Kubernetes manifests | `kubectl kustomize infrastructure/kubernetes/overlays/dev` |

New database changes go in `database/migrations/postgresql` — see [database/README.md](database/README.md).
New backend features go inside an existing module or a new one — see [ARCHITECTURE.md](ARCHITECTURE.md#backend-modules).

## Configuration

Configuration follows the 12-factor model: every environment-specific value is an environment variable.

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
| `backend.yml` | changes in `apps/backend`, `database` | build, unit, modularity and integration tests, SonarQube, image build + Trivy scan, push on `main` |
| `frontend.yml` | changes in `apps/frontend` | format, lint, typecheck, build, SonarQube, image build + Trivy scan, push on `main` |
| `infrastructure.yml` | changes in `infrastructure`, `compose.yaml` | Terraform fmt/validate/tflint, kubeconform on all overlays, compose validation |
| `security.yml` | every PR/push, weekly | gitleaks, dependency review, Trivy filesystem + IaC misconfiguration scan |
| `deploy.yml` | manual | promotes an immutable image tag to dev / staging / prod (GitHub environment protection) |

Images are published to GHCR as `ghcr.io/<owner>/platform-{backend,frontend}:sha-<git sha>` with SBOM and provenance.
