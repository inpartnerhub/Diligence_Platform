# Architecture

This document describes the system's structure, its components and how they interact. Decisions behind it are
recorded as ADRs in [docs/adr](docs/adr).

## 1. Context

```
                         ┌──────────────────────────────┐
   Browser ──HTTPS──────▶│ Kong API Gateway              │──────────────┐
      │                  │ TLS, routing, rate limits,     │              │
      │ OIDC (PKCE)      │ request IDs, metrics, tracing  │              │
      ▼                  └──────┬─────────────────┬───────┘              │
 ┌──────────┐                   │ app.*           │ api.* /api/v1        │
 │ Keycloak │◀── JWKS ──┐       ▼                 ▼                      │
 │ (OIDC)   │           │  ┌──────────┐  server-side  ┌────────────────┐  │
 └──────────┘           │  │ Frontend │──── fetch ───▶│ Backend        │  │
                        └──│ Next.js  │  (via Kong)   │ Spring Boot    │◀─┘
                           └──────────┘               │ modular        │
                                                      │ monolith       │
                                                      └───────┬────────┘
       ┌───────────────┬───────────────┬──────────────┬───────┴──────┬──────────────┬──────────────┐
       ▼               ▼               ▼              ▼              ▼              ▼              ▼
  PostgreSQL        Redis        S3 storage       RabbitMQ      OpenSearch     Tika + OCR       ClamAV
  (Flyway)         (cache)       (documents)      (events)      (search)       (extraction)     (malware)

  Cross-cutting: Vault (secrets) · OpenTelemetry → Collector → OpenSearch (traces, logs)
                 Prometheus → Grafana (metrics, dashboards) · Sentry (errors)
```

## 2. Technology map

| Concern | Technology | Where it is configured |
|---------|-----------|------------------------|
| Web UI | Next.js 16 (App Router), React 19, TypeScript, Ant Design 6 | `apps/frontend` |
| API | Spring Boot 4.1 (Java 21), REST, OpenAPI 3 via springdoc | `apps/backend` |
| Module boundaries | Spring Modulith (verified by `ModularityTests`) | `apps/backend/src/main/java/.../<module>/package-info.java` |
| Relational data | PostgreSQL 17, Hibernate (validate only), Flyway | `database/migrations`, `application.yml` |
| Cache | Redis (Valkey in AWS) via Spring Cache | `application.yml` |
| Object storage | S3 API (MinIO locally, S3 in AWS) via AWS SDK v2 | `document/internal/S3DocumentStorage` |
| Search | OpenSearch via opensearch-java | `search/internal/OpenSearchConfiguration` |
| Messaging | RabbitMQ (quorum queues, DLQs) + Modulith transactional outbox | `ingestion/internal/IngestionMessagingConfiguration` |
| Text extraction / OCR | Apache Tika Server (`-full` image bundles Tesseract) | `ingestion` ports, `TIKA_URL` |
| Malware scanning | ClamAV `clamd` over TCP | `ingestion` ports, `CLAMAV_HOST` |
| Identity | Keycloak (OIDC; Authorization Code + PKCE) | `infrastructure/docker/keycloak/platform-realm.json` |
| Authorization | RBAC (Keycloak roles) + ABAC (`AccessPolicy` beans) | `security` module |
| Gateway | Kong (DB-less locally, Kong Ingress Controller in Kubernetes) | `infrastructure/docker/kong`, `kubernetes/base/gateway` |
| Secrets | HashiCorp Vault + Vault Secrets Operator | `kubernetes/base/backend/vault-secret.yaml`, Terraform stack |
| Runtime | Docker images, Kubernetes (Kustomize), AWS via Terraform | `apps/*/Dockerfile`, `infrastructure/` |
| Delivery | GitHub Actions, SonarQube, Trivy, gitleaks | `.github/workflows` |
| Observability | OpenTelemetry, Prometheus, Grafana, OpenSearch, Sentry | `application.yml`, `infrastructure/docker/*` |

## 3. Backend: modular monolith

One deployable, internally divided into modules with explicit, verified boundaries. This gives the operational
simplicity of a monolith with a clean path to extracting a module into a service later (see
[ADR-0001](docs/adr/0001-modular-monolith.md)).

### Backend modules

Each direct sub-package of `com.example.platform` is a module. Its top-level package is the **public API**;
anything under `internal` is private and cannot be referenced by other modules (the build fails if it is).

| Module | Responsibility | Public API (today) | Owns |
|--------|---------------|--------------------|------|
| `shared` | Shared kernel: error model, API paths, messaging names, OpenAPI config. Open module. | `ApiPaths`, `MessagingTopology`, `GlobalExceptionHandler` | — |
| `security` | JWT authentication, Keycloak role mapping, RBAC, ABAC policy evaluation | `abac.AccessPolicy`, `abac.AccessPolicyEvaluator`, `abac.AccessRequest` | — |
| `document` | Document metadata and binary storage | `DocumentStorage`, `DocumentUploaded` event | schema `document`, S3 bucket |
| `ingestion` | Async pipeline: scan → extract → OCR → hand-off to search | `ContentScanner`, `TextExtractor` | schema `ingestion`, ingestion queues |
| `search` | Full-text indexing and querying | `SearchIndex` | OpenSearch indices `platform-*` |
| `audit` | Append-only audit trail built from other modules' events | — | schema `audit` |

Allowed dependencies are declared in each `package-info.java` and enforced by the build; for example `ingestion`
may depend only on `document`, `search`, `shared` and the `security :: abac` named interface.

### Module rules

1. **Communicate by events first.** A module publishes domain events (`ApplicationEventPublisher`); others react
   with `@ApplicationModuleListener`. Events are stored in the `event_publication` outbox table in the same
   transaction, so nothing is lost if the broker or a listener is down.
2. **Synchronous calls go through the public API only** (interfaces in the module's root package).
3. **Each module owns its data**: its own PostgreSQL schema, no cross-schema foreign keys, no shared tables.
4. **Integration events** (`@Externalized`) are published to RabbitMQ automatically from the outbox — the same
   events can be consumed by other systems or by a module once it is extracted into a service.
5. **Adapters are internal.** S3, OpenSearch, Tika and ClamAV clients live in `internal` packages behind ports,
   so infrastructure can change without touching other modules.

### Request flow

```
Browser ─▶ Next.js (server component / route handler)
            └─▶ apiFetch() with user's access token ─▶ Kong ─▶ Backend
                                                               ├─ SecurityFilterChain: validate JWT
                                                               │   (issuer, signature via JWKS, audience)
                                                               ├─ Role mapping: realm/client roles → ROLE_*
                                                               ├─ @PreAuthorize (RBAC) on controller
                                                               ├─ AccessPolicyEvaluator (ABAC) in service
                                                               └─ Module logic → repository / ports
```

### Document ingestion flow (target design)

```
POST /api/v1/documents ─▶ document: store binary in S3, metadata in PostgreSQL
                          └─ publish DocumentUploaded (outbox, same transaction)
                                │  externalized
                                ▼
            RabbitMQ  platform.document.events ──(document.uploaded)──▶ platform.ingestion.document-uploaded
                                                                          │ (quorum queue, DLQ on failure)
                                                                          ▼
                          ingestion: ContentScanner (ClamAV) ── infected ─▶ quarantine + audit event
                                     │ clean
                                     ▼
                                     TextExtractor (Tika; Tesseract OCR for images / scanned PDFs)
                                     │
                                     ▼
                          search:    SearchIndex.index(...) ─▶ OpenSearch
```

Failed messages are retried three times, then dead-lettered to `platform.ingestion.document-uploaded.dlq`
for inspection and replay.

## 4. Frontend

- **App Router** with server components by default. Ant Design components that rely on compound statics
  (`Typography.Title`, `Form.Item`, …) are used from client components.
- **Ant Design** is wired through `@ant-design/nextjs-registry` (SSR style extraction) and a single theme in
  `src/theme/themeConfig.ts`. CSS variables mode is enabled.
- **API access is server-side only**: `src/lib/api/client.ts` (`server-only`) calls the backend through Kong
  and maps RFC 9457 problem details to `ApiError`. Response types are generated from the backend's OpenAPI spec
  (`npm run generate:api` → `schema.gen.ts`), so the spec is the contract between the two apps.
- **Authentication boundary:** OIDC Authorization Code + PKCE against the `platform-frontend` Keycloak client;
  tokens carry the `platform-backend` audience. The session/token-handling library is chosen with the first
  authenticated feature; it must keep tokens in HTTP-only cookies, never in `localStorage`.
- **Features** live in `src/features/<feature>` mirroring backend modules; see `src/features/README.md`.
- Output is a `standalone` server bundle; health probe at `/api/health`.

## 5. Security architecture

| Layer | Control |
|-------|---------|
| Edge | Kong: TLS termination, HTTPS redirect, rate limiting (Redis-backed in Kubernetes), request size limits, correlation IDs |
| Identity | Keycloak realm `platform`; short-lived access tokens (5 min); brute-force protection; PKCE required |
| Authentication | Backend validates every JWT itself (signature, issuer, audience) — the gateway is not trusted alone |
| RBAC | Realm roles `platform_user`, `platform_admin`, `platform_operator` + client roles → `ROLE_*`; `@PreAuthorize` |
| ABAC | `AccessPolicy` beans evaluated by `AccessPolicyEvaluator`, deny by default |
| Network | Kubernetes default-deny NetworkPolicies; backend reachable only from Kong; management port only from monitoring |
| Workload | Non-root UID 10001, read-only root filesystem, all capabilities dropped, `restricted` Pod Security Standard |
| Secrets | Vault (KV v2, dynamic DB credentials as next step) → Vault Secrets Operator → env; nothing in git |
| Content | Every upload is malware-scanned before extraction or indexing |
| Supply chain | Trivy image + IaC scans, gitleaks, Dependabot, dependency review, SBOM + provenance on images, SonarQube |

Details and reporting process: [SECURITY.md](SECURITY.md).

## 6. Observability

| Signal | Producer | Path | Consumer |
|--------|----------|------|----------|
| Metrics | Micrometer (`/actuator/prometheus` on :8081), Kong, RabbitMQ | Prometheus scrape (ServiceMonitor in Kubernetes) | Grafana |
| Traces | Micrometer Tracing → OpenTelemetry OTLP; Kong OTel plugin | OTel Collector → OpenSearch `otel-v1-apm-span-*` | Grafana, OpenSearch Dashboards |
| Logs | Structured JSON (ECS) to stdout; OTLP log export | OTel Collector / node log agent → OpenSearch `logs-*` | Grafana, OpenSearch Dashboards |
| Errors | Sentry SDK (backend + frontend), no PII | Sentry | Sentry |
| Module events | Spring Modulith actuator + observability | `/actuator/modulith`, spans per module | Grafana |

Trace context (`traceparent`) and `X-Request-Id` propagate from Kong through the backend and into RabbitMQ messages.
The collector scrubs `Authorization` and `Cookie` attributes before export.

## 7. Deployment

```
GitHub Actions ──build/test/scan──▶ GHCR (sha-tagged images, SBOM, provenance)
      │
      └─ deploy.yml (manual, environment-protected) ──kustomize edit set image──▶ kubectl apply -k overlays/<env>

Terraform (environments/<env>) ──▶ VPC, EKS, RDS PostgreSQL, ElastiCache, Amazon MQ (RabbitMQ), OpenSearch, S3
                               └─▶ Vault kv/platform/<env>/backend/infrastructure
Cluster add-ons (GitOps/Helm): Kong Ingress Controller, Vault Secrets Operator, cert-manager,
                               kube-prometheus-stack, OpenTelemetry Collector, Tika and ClamAV workloads
```

| Environment | Namespace | Replicas (min) | Notes |
|-------------|-----------|----------------|-------|
| local | compose | 1 | Security plugins relaxed (OpenSearch), Vault dev mode |
| dev | `platform-dev` | 1 | Full tracing, API docs on |
| staging | `platform-staging` | 2 | Production-like, API docs on |
| prod | `platform-prod` | 3 | Multi-AZ data services, API docs off, approval-gated deploys |

Database migrations run on backend startup (Flyway, advisory-locked) and must be backward compatible with the
previous release so rolling updates are safe.

## 8. Integration boundaries summary

| Boundary | Contract | Owner |
|----------|----------|-------|
| Frontend ↔ Backend | OpenAPI 3 spec at `/v3/api-docs`, `/api/v1` versioning, RFC 9457 errors | backend |
| Client ↔ Gateway | Kong routes `/api/v1/*`; rate/size limits | platform-ops |
| Backend ↔ Keycloak | OIDC discovery/JWKS, `aud=platform-backend`, role claims | security |
| Module ↔ Module | Public package API + domain events; verified by `ModularityTests` | each module |
| Backend ↔ RabbitMQ | `MessagingTopology` names; JSON events; DLQ per queue | publishing / consuming module |
| Backend ↔ Storage/Search/Tika/ClamAV | Ports in module API, adapters in `internal` | owning module |
| Backend ↔ PostgreSQL | Flyway migrations in `database/`, one schema per module | owning module |
| App ↔ Secrets | Environment variables only; populated by Vault | platform-ops |
