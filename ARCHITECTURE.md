# Architecture

How the Diligence Platform is structured and how its parts interact. Requirements come from the PRD
(see [docs/prd](docs/prd/README.md)); decisions are recorded as ADRs in [docs/adr](docs/adr).

## 1. Context

```
                         ┌──────────────────────────────┐
   Browser ──HTTPS──────▶│ Kong API Gateway              │
   (consultant, client,  │ TLS, routing, rate limits,     │
    target, auditor)     │ request IDs, metrics, tracing  │
      │                  └──────┬───────────────────────┬─┘
      │ OIDC redirect           │ app.*                 │ api.* /api/v1
      ▼                         ▼                       ▼
 ┌──────────┐  code+PKCE  ┌──────────────┐  server-side  ┌──────────────────┐
 │ Keycloak │◀───────────│ Frontend BFF  │── fetch with ─▶│ Backend          │
 │ OIDC,MFA,│  tokens     │ Next.js       │  user token   │ Spring Boot      │
 │ SSO (Entra│───────────▶│ session in    │  (via Kong)   │ modular monolith │
 │ /Google) │             │ Redis         │               └────────┬─────────┘
 └──────────┘◀── JWKS / Admin API (service account) ───────────────┤
                                                                    │
       ┌──────────────┬──────────────┬─────────────┬────────────────┼──────────────┬──────────────┬─────────┐
       ▼              ▼              ▼             ▼                ▼              ▼              ▼         ▼
  PostgreSQL       Redis        S3 storage     RabbitMQ        OpenSearch    Tika + OCR      Gotenberg   ClamAV
  (Flyway, RLS)  (cache,       (documents,    (events,        (full-text    (extraction,    (Office →   (malware)
                  sessions)     previews)      DLQs)           search)       Tesseract)      PDF)

  Cross-cutting: Vault (secrets) · SMTP (Mailpit locally) · OpenTelemetry → Collector → OpenSearch (traces, logs)
                 Prometheus → Grafana (metrics) · Sentry (errors)
```

## 2. Technology map

| Concern | Technology | Where it is configured |
|---------|-----------|------------------------|
| Web UI | Next.js 16 (App Router), React 19, TypeScript, Ant Design 6, next-intl (id/en) | `apps/frontend` |
| Web authentication | Backend-for-frontend: openid-client (code + PKCE), Redis session store, iron-session cookie | `apps/frontend/src/lib/auth` |
| API | Spring Boot 4.1 (Java 21), REST, OpenAPI 3 via springdoc | `apps/backend` |
| Module boundaries | Spring Modulith (verified by `ModularityTests`) | `apps/backend/src/main/java/.../<module>/package-info.java` |
| Relational data | PostgreSQL 17, Hibernate (validate only), Flyway, row-level security | `database/migrations`, `application.yml` |
| Cache / sessions | Redis (Valkey in AWS) | `application.yml`, `REDIS_URL` |
| Object storage | S3 API (MinIO locally) via AWS SDK v2 | `dataroom/internal/S3DocumentStorage` |
| Search | OpenSearch via opensearch-java | `search/internal/OpenSearchConfiguration` |
| Messaging | RabbitMQ (quorum queues, DLQs) + Modulith transactional outbox | `ingestion/internal/IngestionMessagingConfiguration` |
| Text extraction / OCR | Apache Tika Server (`-full` image bundles Tesseract) | `ingestion` ports, `TIKA_URL` |
| Preview renditions | Gotenberg (LibreOffice → PDF) | `ingestion.PreviewRenderer`, `GOTENBERG_URL` |
| Malware scanning | ClamAV `clamd` over TCP | `ingestion.ContentScanner`, `CLAMAV_HOST` |
| Identity | Keycloak: OIDC, conditional OTP, NDA required action, Entra ID / Google brokering | `infrastructure/docker/keycloak/diligence-realm.json` |
| Authorization | RBAC (7 PRD roles) + ABAC policies + PostgreSQL RLS | `security` module, `V5__row_level_security.sql` |
| E-mail | Spring Mail (SMTP); Keycloak sends account e-mails itself | `notification` module |
| Gateway | Kong (DB-less locally, Kong Ingress Controller in Kubernetes) | `infrastructure/docker/kong`, `kubernetes/base/gateway` |
| Secrets | HashiCorp Vault + Vault Secrets Operator | `kubernetes/base/*/vault-secret.yaml`, Terraform stack |
| Runtime | Docker images, Kubernetes (Kustomize), AWS via Terraform (hosting decision open, ADR-0003) | `apps/*/Dockerfile`, `infrastructure/` |
| Delivery | GitHub Actions, SonarQube, Trivy, gitleaks | `.github/workflows` |
| Observability | OpenTelemetry, Prometheus, Grafana, OpenSearch, Sentry | `application.yml`, `infrastructure/docker/*` |

## 3. Backend: modular monolith

One deployable divided into the PRD's logical modules (PRD 8.1, [ADR-0001](docs/adr/0001-modular-monolith.md)).
Each direct sub-package of `com.inpartnerhub.diligence` is a module; its root package is the **public API**,
anything under `internal` is private, and `ModularityTests` fails the build on violations or cycles.

| Module | PRD | Responsibility | Status |
|--------|-----|---------------|--------|
| `shared` | — | Shared kernel (open): problem details, paging, API paths, messaging names, `AuditableEvent` | ✅ |
| `security` | ADM-02, Bab 3 | JWT validation, Keycloak role mapping, `Role`, `CurrentUserProvider`, ABAC API, RLS transaction context | ✅ |
| `admin` | ADM-01 | Organizations, user accounts, groups, invitations; Keycloak Admin API adapter; `/api/v1/me` | ✅ sprint 1–2 |
| `audit` | ADM-03, ADM-04 | Append-only audit log fed by every `AuditableEvent`; search API | ✅ sprint 1–2 |
| `notification` | ADM-05, REQ-04 | E-mail port + SMTP adapter, notification preferences | 🟡 skeleton |
| `project` | PRJ | Projects, members, workstreams, templates, dashboards | ⏳ schema only (sprint 3–4) |
| `dataroom` | DOC | Folders, documents, versions, watermarked preview, download control | ⏳ storage port (sprint 5–8) |
| `ingestion` | DOC-02/03/06 | Quarantine → scan → extract/OCR → PDF rendition → index | ⏳ ports + queues |
| `search` | DOC-06 | Full-text search with permission filtering | ⏳ client config |
| `request` | REQ | Request lists, statuses, reminders, Excel import/export | ⏳ schema only (sprint 9–10) |
| `finding` | FND | Findings, risk register; risk matrix (Appendix A) implemented | 🟡 `RiskMatrix` (sprint 11–12) |
| `qna` | QNA | Questions with approval before release to the target | ⏳ schema only (sprint 13–14) |
| `report` | RPT | Word/PDF reports from released findings, approvals | ⏳ schema only (sprint 13–14) |

### Module rules

1. **Events first.** Modules publish domain events; others react with `@ApplicationModuleListener`. Events are
   stored in the `event_publication` outbox in the same transaction, so nothing is lost if a listener fails.
2. **Synchronous calls go through the public API only** (types in the module's root package).
3. **Each module owns a PostgreSQL schema** (`adm`, `audit`, `ntf`, `prj`, `req`, `dr`, `fnd`, `qna`, `rpt`);
   no cross-schema foreign keys.
4. **Integration events** (`@Externalized`) are published to RabbitMQ from the outbox.
5. **Adapters are internal.** Keycloak, S3, OpenSearch, Tika, Gotenberg and ClamAV clients sit behind ports.
6. **Auditable actions** publish an event implementing `shared.audit.AuditableEvent`; the audit module records it
   with actor, IP and user agent captured at publish time.

## 4. Roles and information barriers (PRD Bab 3)

Global roles live in Keycloak (realm roles); per-project roles live in `prj.project_member` (optionally per
workstream, with an expiry for external access).

| Role | Group | MFA | NDA | Sees |
|------|-------|-----|-----|------|
| `SUPER_ADMIN` | Internal IT | ✅ | — | Administration, audit log, templates |
| `PARTNER` | Consultant | SSO | — | All of their projects, approves reports, portfolio |
| `PROJECT_MANAGER` | Consultant | SSO | — | Projects they manage: request lists, team, Q&A |
| `ANALYST` | Consultant | SSO | — | Data room per workstream, findings, risk ratings |
| `CLIENT` | External (buy-side) | ✅ | ✅ | Progress, **released** findings, issued reports, own questions |
| `TARGET` | External (sell-side) | ✅ | ✅ | Request list, assigned folders, **approved** questions — never findings |
| `OBSERVER` | Internal/External auditor | ✅ | ✅ | Read-only |

Enforcement is layered so a UI bug cannot leak data:

1. **Frontend** shows navigation per role (`src/lib/auth/permissions.ts`) — convenience only.
2. **API** checks roles with `@PreAuthorize` and resource rules with ABAC `AccessPolicy` beans (deny by default).
3. **Database** row-level security ([ADR-0004](docs/adr/0004-row-level-security.md)): every transaction runs as the
   non-owner role `diligence_app` with `app.user_id` / `app.role` set by `RowLevelSecurityTransactionManager`.
   Policies in `V5__row_level_security.sql` make other projects invisible and enforce the barriers: the target
   never reads `fnd.*`, the client reads only `RELEASED` findings, `ISSUED` reports and its own questions, the target
   reads only `SENT`/`ANSWERED` questions. The integration test `rowLevelSecurityEnforcesInformationBarrier` proves it.
4. **Audit**: `audit.audit_log` accepts only INSERT/SELECT from the runtime role and triggers reject UPDATE, DELETE
   and TRUNCATE for everyone.

## 5. Authentication flows

### Browser (BFF, [ADR-0005](docs/adr/0005-bff-authentication.md))

```
Browser ─GET /admin/users─▶ Next proxy.ts: no diligence_sid cookie → 307 /auth/login?returnTo=…
        ─GET /auth/login─▶ PKCE verifier + state in sealed cookie (10 min) → 307 Keycloak /auth
Keycloak: password → OTP if role has mfa_required → NDA (first login, externals) → 302 /auth/callback?code
        ─GET /auth/callback─▶ code exchange (back-channel) → tokens stored in Redis under a random session id,
                              cookie diligence_sid = sealed id (HttpOnly, SameSite=Lax, Secure on HTTPS)
Server components / actions ─▶ getSession(): refresh access token when < 30 s left → apiFetch(Bearer) → Kong → API
POST /auth/logout (same-origin only) ─▶ delete Redis session → Keycloak end-session → back to app
```

Tokens never reach the browser. The OIDC client builds browser-facing endpoints from `OIDC_ISSUER` and back-channel
endpoints from `OIDC_INTERNAL_ISSUER`, so container networks and the token `iss` claim stay consistent.

### API

The backend is a stateless resource server: it validates signature (JWKS), issuer and audience (`diligence-api`)
of every token, maps realm and client roles to `ROLE_*`, and publishes `UserAuthenticated` (throttled per user) so
the admin module records the login, activates invited users and provisions SSO users just in time.

### Invitations (ADM-01)

`POST /api/v1/admin/users/invitations` → Keycloak Admin API (service account `diligence-admin-api`): create user →
set realm role → `execute-actions-email` with `VERIFY_EMAIL`, `UPDATE_PASSWORD`, plus `TERMS_AND_CONDITIONS`
(NDA, externals) and `CONFIGURE_TOTP` (MFA roles) → mirror row in `adm.user_account` + `adm.invitation` →
`UserInvited` audited. If any step fails, the Keycloak user is deleted (compensation).

## 6. Document pipeline (PRD 8.3, target design for sprint 5–8)

```
Upload (API or pre-signed URL) ─▶ dataroom: object in S3 quarantine prefix, metadata (scan_status=PENDING)
   └─ DocumentUploaded (outbox) ─▶ RabbitMQ diligence.dataroom.events ─▶ diligence.ingestion.document-uploaded
        ingestion worker:
          ContentScanner (ClamAV) ── infected ─▶ scan_status=INFECTED, quarantine kept, audit + notify
          │ clean: validate type (magic bytes), SHA-256 (duplicate detection, dr.document.sha256)
          TextExtractor (Tika; Tesseract OCR for scans/images)
          PreviewRenderer (Gotenberg: Office/image → PDF rendition, preview_key)
          SearchIndex (OpenSearch, document ACL fields for permission-filtered queries)
View/download ─▶ API checks access (RBAC + folder policy + RLS) ─▶ Watermarker stamps name, time, IP
               ─▶ short-lived stream / pre-signed URL ─▶ audit "document.viewed" / "document.downloaded"
```

Failures retry three times, then dead-letter to `diligence.ingestion.document-uploaded.dlq`.

## 7. Frontend

- App Router; `src/app/(app)` is the authenticated area rendered inside `AppShell` (Ant Design layout, role-based
  navigation from PRD Bab 11.1, language switch, user menu). Pages for later sprints show their sprint and PRD IDs.
- Server components fetch through `apiFetch` (server-only); mutations use server actions
  (`src/features/<feature>/actions.ts`). Filters and paging live in the URL.
- Ant Design compound components are used from client components; one theme in `src/theme/themeConfig.ts`,
  including risk colors from Appendix A.
- i18n: `next-intl` with `messages/id.json` (default) and `messages/en.json`; the language is a cookie, synced to the
  user profile (`PATCH /api/v1/me`) and applied to Ant Design and Keycloak (`ui_locales`).

## 8. Observability

| Signal | Producer | Path | Consumer |
|--------|----------|------|----------|
| Metrics | Micrometer (`:8081/actuator/prometheus`), Kong, RabbitMQ | Prometheus (ServiceMonitor in Kubernetes) | Grafana |
| Traces | Micrometer Tracing → OTLP; Kong OTel plugin; AMQP propagation | OTel Collector → OpenSearch | Grafana, OpenSearch Dashboards |
| Logs | Structured JSON (ECS) to stdout, OTLP | OTel Collector → OpenSearch | Grafana, OpenSearch Dashboards |
| Errors | Sentry SDK (backend + frontend), no PII | Sentry | Sentry |
| Security events | Keycloak login events, `audit.audit_log` | Keycloak event store, PostgreSQL | Admin → Audit log, alerting |

## 9. Deployment

```
GitHub Actions ──build/test/scan──▶ GHCR (sha-tagged images, SBOM, provenance)
      └─ deploy.yml (manual, environment-protected) ──kustomize edit set image──▶ kubectl apply -k overlays/<env>

Terraform (environments/<env>) ──▶ network, Kubernetes, PostgreSQL, Redis, RabbitMQ, OpenSearch, object storage
                               └─▶ Vault kv/diligence/<env>/backend/infrastructure
Cluster add-ons (GitOps/Helm): Kong Ingress Controller, Vault Secrets Operator, cert-manager,
                               kube-prometheus-stack, OpenTelemetry Collector, Tika, Gotenberg and ClamAV workloads
```

| Environment | Namespace | Min replicas | Notes |
|-------------|-----------|--------------|-------|
| local | compose | 1 | OpenSearch security off, Vault dev mode, Keycloak `start-dev`, Mailpit |
| dev | `diligence-dev` | 1 | Full tracing, API docs on |
| staging | `diligence-staging` | 2 | Production-like, API docs on |
| prod | `diligence-prod` | 3 | Multi-AZ data services, API docs off, approval-gated deploys |

Hosting model and data residency (UU PDP) are still open — see [ADR-0003](docs/adr/0003-hosting-and-data-residency.md).
Migrations run at backend startup (Flyway advisory lock) and must be backward compatible with the previous release.

## 10. Integration boundaries

| Boundary | Contract | Owner |
|----------|----------|-------|
| Browser ↔ Frontend | HTML/RSC + server actions; only an opaque session cookie | frontend |
| Frontend ↔ Backend | OpenAPI 3 at `/v3/api-docs`, `/api/v1`, RFC 9457 errors, bearer token | backend |
| Client ↔ Gateway | Kong routes `/api/v1/*`; rate/size limits | ops |
| Apps ↔ Keycloak | OIDC (code + PKCE), JWKS, `aud=diligence-api`, realm roles; Admin REST via service account | security |
| Module ↔ Module | Public package API + domain events; verified by `ModularityTests` | each module |
| Backend ↔ RabbitMQ | `MessagingTopology` names; JSON events; DLQ per queue | publishing / consuming module |
| Backend ↔ Storage/Search/Tika/Gotenberg/ClamAV | Ports in module API, adapters in `internal` | owning module |
| Backend ↔ PostgreSQL | Flyway migrations in `database/`, schema per module, RLS context per transaction | owning module |
| Apps ↔ Secrets | Environment variables only; populated by Vault | ops |
