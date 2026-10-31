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
| Object storage | S3 API (SeaweedFS locally) via AWS SDK v2 | `dataroom/internal/S3DocumentStorage` |
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
| `notification` | ADM-05, REQ-04 | `Notifier` API: in-app inbox and e-mail per user preference; SMTP adapter | ✅ sprint 9–10 |
| `project` | PRJ | Projects, members, workstreams, templates, dashboards | ✅ sprint 3–4 |
| `dataroom` | DOC | Folder tree with per-folder grants, uploads, versions, duplicates; watermarked preview, download control, search | ✅ sprint 5–8 |
| `ingestion` | DOC-02/03/06 | RabbitMQ worker: ClamAV scan, Tika type detection, text/OCR, PDF rendition, page images | ✅ sprint 5–8 |
| `search` | DOC-06 | Full-text search with permission filtering | ⏳ client config |
| `request` | REQ | Request lists from template/Excel, waves, six-status workflow, delivery by the target, reminders, Excel/PDF export | ✅ sprint 9–10 |
| `finding` | FND | Findings with impact × likelihood rating (configurable matrix), source documents with pages, four-eyes review and partner release | ✅ sprint 11–12 |
| `qna` | QNA | Questions linked to documents, request items or findings; approval before the target sees them; answers with documents; Excel/PDF log | ✅ sprint 13–14 |
| `report` | RPT | Word/PDF reports from released findings (summary, heat map, findings per workstream), versions, partner issue, watermarked client download | ✅ sprint 13–14 |

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
   reads only `SENT`/`ANSWERED` questions, released request items (V9) and never internal request notes. Finding
   review notes, history and source links are internal only, and released findings are immutable (V10). Answers sit
   in `qna.answer`, which the client reads only for its own closed questions; issued reports are immutable (V11). The integration test `rowLevelSecurityEnforcesInformationBarrier` proves it.
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

## 6. Document pipeline (PRD 8.3; scan since sprint 5–6, preview/watermark/search since sprint 7–8)

```
Browser ─▶ Next route handler (streams, adds token) ─▶ POST /folders/{id}/documents (multipart, relativePath)
   dataroom: SHA-256 while streaming to S3 (KMS-encrypted) → version row, scan_status=PENDING (logical quarantine:
             nothing PENDING/INFECTED/REJECTED/FAILED is ever served); same name → new version; same hash → unchanged
   └─ DocumentUploaded (outbox, same transaction) ─▶ RabbitMQ diligence.dataroom.events ─▶ diligence.ingestion.document-uploaded
        ingestion worker (@RabbitListener, JSON):
          ContentScanner (ClamAV INSTREAM) ── infected ─▶ INFECTED (signature recorded, audited)
          │ clean: ContentTypeDetector (Tika 4 /detect or Tika 3 /detect/stream, magic bytes) ── not on allowlist ─▶ REJECTED
          │ allowed ─▶ enrich, then CLEAN          transient scan outage ×3 ─▶ FAILED (never stuck in PENDING)
          │   TextExtractor (Tika /tika, Tesseract OCR for images and scanned pages) ─▶ renditions/{v}/text.txt
          │   PDF: original │ Office/RTF/text: PreviewRenderer (Gotenberg LibreOffice) │ images: PDFBox
          │     ─▶ PageRasterizer (PDFBox, ≤ 300 pages, ≤ 2400 px) ─▶ renditions/{v}/pages/NNNN.png
          │     ─▶ non-PDF originals also keep renditions/{v}/document.pdf for downloads
          │   enrichment failures degrade (no preview / no text), they never fail a clean document
   ScanResults.recordClean ─▶ SearchIndexRequested (event registry, after commit) ─▶ SearchIndexer ─▶ OpenSearch
View ─▶ GET /documents/{d}/preview (audit "document.viewed") ─▶ GET …/versions/{v}/pages/{n}: stored PNG stamped
        per request with name · e-mail · IP · time (Watermarker, Java2D); the PDF never reaches the browser
Download ─▶ folder policy (deny by default; partners/PMs exempt) ─▶ watermarked PDF (PDFBox; print permission
            removed and AES-256 owner password when printing is not allowed) or the original (internal team,
            or types without a PDF form) ─▶ audit "document.downloaded"
Search ─▶ GET /projects/{p}/search ─▶ OpenSearch query filtered to the project and the folders the caller can see
          (RLS + workstream scope), hits re-checked against the database ─▶ highlights marked U+E000…U+E001
```

The index (`<prefix>-documents`) analyses content three ways (standard, Indonesian and English stemming). OCR uses
`OCR_LANGUAGES` (default `eng`); the stock `apache/tika:*-full` image ships eng/deu/fra/ita/spa/jpn, so Indonesian
trained data (`ind`) needs a derived image with `tesseract-ocr-ind`. Viewer watermark times use `WATERMARK_ZONE`
(default Asia/Jakarta).

Unexpected errors in the worker are retried by the listener container, then dead-lettered to
`diligence.ingestion.document-uploaded.dlq`. The worker can be scaled separately later by running the same image
with only the ingestion listener enabled.

## 7. Request list and notifications (PRD REQ-01…REQ-06, ADM-05; sprint 9–10)

```
Project created from a template ─▶ ProjectCreated (sync) ─▶ request: items per workstream, numbered LEG-001 …
Internal team: add/edit items, Excel import (dry run, all-or-nothing), waves 1–20
Release (PM/partner) ─▶ NOT_REQUESTED → REQUESTED, history row ─▶ RequestItemsReleased ─┐
Target: attach data-room documents (upload or pick; DocumentDirectory checks folder access) │ audit log
        ─▶ report PARTIAL / RECEIVED ─▶ RequestStatusChanged(byTarget) ─────────────────────┤ (AuditableEvent)
Team: close, or reject with a reason the target sees ─▶ RequestStatusChanged ──────────────┤
Comments: public or internal notes (internal never reach the target: RLS + no notification) ┘
   └─ @ApplicationModuleListener (after commit, system context) ─▶ RequestNotifications: recipients from the
      project roster (assignee, else target members of the workstream; managers + workstream analysts)
      ─▶ Notifier ─▶ ntf.notification (inbox, header bell polls the unread count) + e-mail per preference
Scheduler (REQUEST_REMINDER_CRON, ShedLock: one replica) ─▶ RequestReminderService: open items due within
      REQUEST_REMINDER_DAYS_BEFORE days or overdue, each at most once per day ─▶ one reminder per target per
      project + overdue digest for managers
Export ─▶ XLSX (Apache POI, the same columns the import reads) or PDF (the XLSX converted by Gotenberg) ─▶ audited
```

Statuses (`RequestStatus`): the internal team moves items along the workflow; the target only reports `PARTIAL` or
`RECEIVED` on open items (`REQUESTED`, `PARTIAL`, `REJECTED`) once at least one document is attached. `REJECTED`
means the delivery was not sufficient and the item is open again. Every change is a row in
`req.request_item_status_history` (KPI "kick-off → 80% fulfilled", REQ-06 history). Row-level security (V9) hides
unreleased items and internal notes from the target and lets it neither create, delete, hide nor close items. The
client side has no request list; it follows progress on the project dashboard.

## 8. Findings and risk register (PRD FND-01…FND-04; sprint 11–12)

```
Analyst: record finding (draft, numbered F-LEG-001) ── or from a page in the document viewer (source pre-filled)
   rate impact × likelihood ─▶ RiskMatrix (thresholds in fnd.risk_matrix_config) ─▶ score + level
   HIGH/CRITICAL reached ─▶ FindingEscalated ─▶ PMs (HIGH) or partners and PMs (CRITICAL)   (Appendix A)
   cite source documents with page/section (DocumentDirectory: only documents the analyst may open)
Submit (rated + ≥ 1 source) ─▶ IN_REVIEW ─▶ reviewers notified; internal review notes
Review (PM/partner, not the author: four eyes) ─▶ APPROVED (partners notified) │ return to DRAFT with a reason
Release (partners only) ─▶ RELEASED ─▶ client notified; visible to the client from now on (RLS)
Released findings are final: only a partner archives them, with a reason. Every step is audited and kept in
fnd.finding_status_history.
Admin: PUT /admin/risk-matrix ─▶ fnd.recompute_risk_levels() re-rates open findings; released ones keep their level
```

Row-level security (V10) makes the rules hold below the API: the target reads no `fnd.*` row; the client reads
released findings only and never their review notes, history or source links; released and archived findings cannot
be updated by the team (only archived by a partner); only partners can set `RELEASED`. The findings page shows a 5 × 5
heat map in the configured thresholds and the share of findings with a source document (KPI ≥ 95%).

## 9. Q&A and reports (PRD QNA-01…QNA-03, RPT-01; sprint 13–14)

```
Team: draft question (Q-001; links to a request item, document or finding) ─▶ submit ─▶ PENDING_APPROVAL
Client: ask ─▶ PENDING_APPROVAL (numbers via qna.next_question_number(): the client cannot see others' questions)
PM/partner: approve and send ─▶ SENT ─▶ target notified    │ return with feedback ─▶ DRAFT (author notified)
Target: answer (+ documents uploaded or picked in the data room) ─▶ ANSWERED ─▶ team notified
Team: close ─▶ CLOSED (a client asker is notified and can now read the answer) │ follow-up ─▶ SENT with feedback
Export: GET /questions/export?format=xlsx|pdf — the log as the caller sees it (Apache POI, PDF via Gotenberg)

PM/partner: generate ─▶ FindingDirectory.released() ─▶ ReportDocumentWriter (Apache POI XWPF: cover with the target company, executive
            summary, 5 × 5 heat map, findings per workstream by risk with sources) ─▶ Gotenberg PDF
            ─▶ object storage projects/{p}/reports/{id}.docx|.pdf ─▶ rpt.report (version n, DRAFT) + rpt.report_finding
Partner: issue ─▶ ISSUED (immutable, RLS) ─▶ client notified
Download: team ─▶ Word or PDF; client ─▶ issued PDF stamped by dataroom.PdfWatermark (name, e-mail, IP, time); audited
```

The finding link of a question is never returned to the target or the client, and the target never sees drafts or
questions awaiting approval (RLS on `qna.question`). Report wording exists in Indonesian and English; the layout is
neutral until the firm's official template is available (PRD Bab 15.2). Tiered report approval (RPT-03) follows in
phase 2.

## 10. Frontend

- App Router; `src/app/(app)` is the authenticated area rendered inside `AppShell` (Ant Design layout, role-based
  navigation from PRD Bab 11.1, language switch, user menu). Pages for later sprints show their sprint and PRD IDs.
- Server components fetch through `apiFetch` (server-only); mutations use server actions
  (`src/features/<feature>/actions.ts`). Filters and paging live in the URL.
- Ant Design compound components are used from client components; one theme in `src/theme/themeConfig.ts`,
  including risk colors from Appendix A. Its CSS is extracted at build time (`scripts/extract-antd-css.mjs`, run
  before `dev` and `build`) and antd runs in zero-runtime mode, so no CSS-in-JS is generated per request.
- i18n: `next-intl` with `messages/id.json` (default) and `messages/en.json`; the language is a cookie, synced to the
  user profile (`PATCH /api/v1/me`) and applied to Ant Design and Keycloak (`ui_locales`).

## 11. Observability

| Signal | Producer | Path | Consumer |
|--------|----------|------|----------|
| Metrics | Micrometer (`:8081/actuator/prometheus`), Kong, RabbitMQ | Prometheus (ServiceMonitor in Kubernetes) | Grafana |
| Traces | Micrometer Tracing → OTLP; Kong OTel plugin; AMQP propagation | OTel Collector → OpenSearch | Grafana, OpenSearch Dashboards |
| Logs | Structured JSON (ECS) to stdout, OTLP | OTel Collector → OpenSearch | Grafana, OpenSearch Dashboards |
| Errors | Sentry SDK (backend + frontend), no PII | Sentry | Sentry |
| Security events | Keycloak login events, `audit.audit_log` | Keycloak event store, PostgreSQL | Admin → Audit log, alerting |

## 12. Deployment

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

## 13. Integration boundaries

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
