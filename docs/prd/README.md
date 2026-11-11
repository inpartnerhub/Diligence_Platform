# PRD summary and traceability

Source: *Konsep Produk & Spesifikasi untuk Tim IT — Platform Due Diligence*, v1.0, 30 October 2026
(`Konsep_Platform_Due_Diligence.docx`, kept outside the repository). This page tracks how each requirement maps to
the code. Priorities follow MoSCoW: **M** = MVP must, **S** = should (phase 2 unless noted), **C** = could (phase 3).

## Goals and KPIs (Bab 2)

| KPI | Baseline | Target (6 months after go-live) | Measured from |
|-----|----------|------|---------------|
| Initial data collection (kick-off → 80% of request list fulfilled) | 4–6 weeks | ≤ 3 weeks | `req.request_item` status history |
| Draft report compilation after analysis | 5–7 working days | ≤ 2 days | `rpt.report` timestamps |
| Findings linked to source documents | not measured | ≥ 95% | `fnd.finding_document` |
| Active projects on the platform | 0 | ≥ 80% of new projects | `prj.project` |
| Data leakage incidents | — | 0 | incident register, `audit.audit_log` |
| User satisfaction (1–5) | — | ≥ 4.0 | survey |

## MVP sprint plan (Bab 12.1) and status

| Sprint | Scope | Status |
|--------|-------|--------|
| 1–2 | Foundation: repo, CI/CD, environments, authentication & SSO, user and role management, UI shell | ✅ delivered |
| 3–4 | Projects, members, templates, workstreams; core data model and audit log | ✅ delivered |
| 5–6 | Data room: upload, encrypted storage, malware scan, folders, permissions | ✅ delivered |
| 7–8 | Preview, dynamic watermark, download control, search and OCR | ✅ delivered |
| 9–10 | Request list: import/export, statuses, notifications and reminders, target-side UI | ✅ delivered |
| 11–12 | Findings, source document links, risk rating, validation workflow | ✅ delivered |
| 13–14 | Q&A with approval; report builder (Word/PDF) from templates | ✅ delivered |
| 15–16 | Hardening: performance, penetration test, fixes, UAT, documentation, training | ✅ performance, internal pre-pentest, UAT plan and scenarios ([UAT](../uat/README.md)), user guides and training plan ([guides](../guides/README.md)), runbooks and incident response plan ([runbooks](../runbooks/README.md)); external pentest, UAT and the first restore test run by the business |

## Requirement traceability

| ID | Requirement (short) | Prio | Module / location | Status |
|----|---------------------|------|-------------------|--------|
| PRJ-01 | Create/edit/archive projects | M | `project`, `/projects` UI | ✅ |
| PRJ-02 | Team members and roles per project/workstream | M | `MemberService`, team tab | ✅ |
| PRJ-03 | Duplicable project templates | M | `TemplateService`, `/admin/templates` | ✅ |
| PRJ-04 | Project dashboard | M | `DashboardService`, project dashboard tab | ✅ |
| PRJ-05 | Portfolio dashboard | S | `DashboardService`, `/dashboard` UI | ✅ |
| PRJ-06 | Time / workload tracking | C | `TimeEntryService`, time tab, CSV export, RLS V16 | ✅ |
| REQ-01 | Request list from template or Excel | M | `RequestService` (template on `ProjectCreated`), `RequestTransferService` import (dry run) | ✅ |
| REQ-02 | Item statuses (6) | M | `RequestStatus` workflow, `req.request_item_status_history` | ✅ |
| REQ-03 | Target links documents and comments | M | `DocumentDirectory`, `req.request_item_document`, `req.request_item_comment` (internal notes, RLS V9) | ✅ |
| REQ-04 | Automatic reminders (e-mail + in-app) | M | `RequestReminderService` (ShedLock), `RequestNotifications`, `Notifier` | ✅ |
| REQ-05 | Export to Excel/PDF | S (MVP) | `RequestSpreadsheet` (Apache POI), PDF via Gotenberg, audited | ✅ |
| REQ-06 | Staged waves with history | S | release per wave/workstream, status history | ✅ |
| DOC-01 | Folders from template, per-folder permissions | M | `FolderService`, `dr.folder_access`, RLS V7 | ✅ |
| DOC-02 | Bulk upload, indexing, malware scan | M | drag-and-drop/folder upload, RabbitMQ worker, ClamAV, `SearchIndexer` | ✅ |
| DOC-03 | In-browser preview | M | Gotenberg/PDFBox renditions, pre-rendered pages, `DocumentViewer` | ✅ |
| DOC-04 | Dynamic watermark | M | `dataroom.Watermarker`: name, e-mail, IP, time on every page and download | ✅ |
| DOC-05 | Disable download/print per folder; logged downloads | M | `DocumentViewService` policy, PDF print permission, print CSS, audit | ✅ |
| DOC-06 | Full-text search incl. OCR | M | Tika + Tesseract, OpenSearch (`search`), barrier-filtered queries | ✅ |
| DOC-07 | Document versions | S (MVP) | `DocumentService`, versions drawer | ✅ |
| DOC-08 | Annotations linked to findings | S | `dr.document_annotation`, `DocumentViewer` | ✅ |
| DOC-09 | AI search, duplicates, key clauses | C | SHA-256 duplicate detection on upload; rule-based key clause extraction (`KeyClauseExtractor`, English patterns) | 🟡 duplicates and rule-based clauses; AI search/grouping and the AI principles of Bab 10.3 pending |
| FND-01 | Record findings | M | `FindingService`, template categories, `/projects/{id}/findings` | ✅ |
| FND-02 | Link findings to documents (page) and Q&A | M | `fnd.finding_document` (page, viewer shortcut); questions carry `finding_id` | ✅ |
| FND-03 | Impact × likelihood risk rating, configurable | M | `RiskMatrix`, `RiskMatrixService`, `/admin/risk-matrix`, heat map | ✅ |
| FND-04 | Validation workflow Draft → Review → Approved → Released | M | `FindingStatus`, four eyes, partner release, RLS V10 | ✅ |
| FND-05 | Follow-up / mitigation, SPA links | S | `fnd.finding` SPA & remediation fields, drawer & CSV | ✅ |
| FND-06 | Finding library | C | `FindingLibraryService`, import drawer in the register, seeded templates; managed by the Super Admin (migration `V202611050501`) | ✅ |
| QNA-01 | Questions linked to documents/requests/findings | M | `QuestionService`, shortcuts from request items, findings and the viewer | ✅ |
| QNA-02 | Approval before sending to target | M | PM/partner approval, `qna.answer` RLS (client after close), V11 | ✅ |
| QNA-03 | Export and search Q&A log | S (MVP) | search and filters, `QnaExportService` (Excel/PDF) | ✅ |
| RPT-01 | Report builder from selected released findings (Word/PDF) | M | `ReportService` (finding picker, `rpt.report_finding` snapshot), `ReportDocumentWriter`, partner issue, watermarked client PDF | ✅ |
| RPT-02 | Executive summary and risk heat map | S | summary and 5 × 5 heat map in the report | 🟡 basic version |
| RPT-03 | Tiered report approval with versions | S | `rpt.report_approval`, `rpt.report_version` | ✅ |
| RPT-04 | Export findings to PowerPoint/Excel | C | `ReportPresentationWriter` (PPTX), `ReportSpreadsheetWriter` (XLSX), both from the findings of the report version | ✅ |
| ADM-01 | Users, groups, organizations; e-mail invitations | M | `admin`, `/admin/*` UI | ✅ |
| ADM-02 | Strong passwords; MFA for externals and admins; SSO for internal | M | Keycloak realm, `browser-mfa`, bootstrap script | ✅ |
| ADM-03 | Immutable audit trail | M | `audit`, `audit.audit_log` triggers | ✅ |
| ADM-04 | Activity report per user / document | S (MVP) | `GET /api/v1/admin/audit-logs`, `/admin/audit` | ✅ basic |
| ADM-05 | E-mail and in-app notifications, preferences | M | `notification` (`ntf.notification`, preferences), header bell, `/notifications` | ✅ |
| ADM-06 | Retention and automatic deletion | S | `ProjectRetentionJob`; one `*RetentionListener` per module (data room, findings, requests, Q&A, reports, notifications, timesheet), `WebhookLogRetention`; `ProjectPurgeIntegrationTest` (#114) | ✅ |
| ADM-07 | Public API and webhooks | C | OpenAPI; `WebhookService` (HMAC-SHA256, retries, delivery log, HTTPS-only public targets, key shown once, Super Admin only) | ✅ |

## Non-functional requirements (Bab 7)

| Aspect | Target | How it is addressed |
|--------|--------|---------------------|
| Performance | Pages ≤ 2 s P95; preview ≤ 3 s (≤ 20 MB); search ≤ 2 s on 500k docs | Async pipeline, pre-rendered PDF renditions, OpenSearch, Ant Design CSS extracted at build time; k6 in `tests/performance` (local stress results in its README) |
| Scalability | ≥ 200 projects, ≥ 1,000 concurrent users, ≥ 20 TB | Stateless pods + HPA, Redis sessions, S3, OpenSearch |
| Availability | 99.5% business hours; RPO ≤ 1 h, RTO ≤ 4 h | Multi-AZ data services in prod, PITR backups, quarterly restore test |
| Security | TLS 1.2+, AES-256 at rest | SECURITY.md; optional per-project IP allow-list for external users (Bab 10.1) |
| Compliance | UU PDP, ISO/IEC 27001 | SECURITY.md, ADR-0003 |
| Usability | Indonesian and English; tablet-responsive; WCAG 2.1 AA for key elements | next-intl, Ant Design responsive layout, accessible labels |
| Observability | Central logs, metrics, tracing; alerts | OpenTelemetry, Prometheus/Grafana, OpenSearch, Sentry; alert rules for error rate, latency, failed/suspicious logins and blocked access (`infrastructure/docker/prometheus/alerts.yml`, `PrometheusRule`) linked to [runbooks](../runbooks/README.md) |
| Maintainability | ≥ 70% unit coverage on business logic; CI/CD; current OpenAPI | Jacoco gate in `build.gradle.kts`, springdoc |

## Open questions (Bab 15.2)

Hosting model and data residency (ADR-0003); internal-only vs. licensed to third parties (affects multi-tenancy and
billing); expected active projects and data-room sizes; official report templates and risk methodology; Product
Owner and user representatives; budget and build-vs-buy for the virtual data room.
