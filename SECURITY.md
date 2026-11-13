# Security Policy

Due diligence documents contain highly sensitive information (financials, contracts, employee data, transaction
plans). Security is a precondition for the product, not a feature (PRD Bab 10).

## Reporting a vulnerability

**Do not open a public issue.** Report suspected vulnerabilities privately through GitHub's
"Report a vulnerability" (Security → Advisories) on this repository, or to the security officer named in the
project governance. Include affected component, version/commit, reproduction steps and impact.

| Stage | Target |
|-------|--------|
| Acknowledgement | 2 business days |
| Triage and severity (CVSS) | 5 business days |
| Fix for Critical / High | 7 / 30 days |

Only the latest release deployed from `main` receives security fixes.

## Mandatory controls (PRD 10.1) and where they are implemented

| Control | Implementation | Status |
|---------|---------------|--------|
| MFA for every user (external users, administrators and, since #161, the internal team) | Every application role carries `mfa_required`. Keycloak browser flow `browser-mfa`: conditional OTP for roles carrying it; SSO logins (Entra ID / Google) run `post-broker-mfa`, which asks for the Keycloak OTP after the identity provider; `CONFIGURE_TOTP` on invitation | ✅ |
| Session expiry on inactivity | Keycloak `ssoSessionIdleTimeout` 30 min, max 10 h; access tokens 5 min; BFF session bounded by the refresh token; disabling a user ends their Keycloak sessions and the API refuses their remaining access tokens at once (`DisabledAccountFilter`, #173) | ✅ |
| Strong passwords, brute-force protection | Realm password policy (length 12, history 5, not username/e-mail, blacklist of 100,000 common/breached passwords from SecLists: `infrastructure/docker/keycloak/password-blacklists`), lockout after 5 failures | ✅ |
| Step-up for sensitive actions | `@RequiresRecentSignIn`: report release/issue, project purge, IP allow-list changes, webhook changes and role changes need a sign-in with MFA within 10 min: `auth_time` within `STEP_UP_MAX_AGE` **and** `amr` containing `otp` (`STEP_UP_MFA_METHOD`; Keycloak `amr` mapper on `diligence-web`, reference values on the password and OTP forms, #160), so a password-only sign-in never satisfies it; the same rule covers bulk downloads; the web app re-authenticates with `prompt=login` | ✅ |
| Internal SSO | Microsoft Entra ID / Google brokering via `make keycloak-bootstrap` (credentials from env/Vault), limited to the firm's tenant / Workspace domain; imported accounts have no role until assigned (#152) | ✅ |
| Server-side RBAC, tested against cross-project access (IDOR) | `@PreAuthorize`, module access components (`ProjectAccess`, `DataRoomAccess`, …), PostgreSQL RLS per project; `AdminEndpointSecurityTest`, `rowLevelSecurityEnforcesInformationBarrier` | ✅ foundation |
| Information barriers (Bab 3) | Project as the security boundary, one operating firm per deployment ([ADR-0008](docs/adr/0008-tenant-model.md)). RLS: target never sees findings and only sees data-room folders granted to it; client has no data room and sees released findings / issued reports / own questions only; identity tables (`adm.*`) are row-level secured as well, so external users never read other organizations' people (#154); CLIENT/TARGET members must belong to the project's client/target organization (database trigger, #155), deactivating an organization ends its members' project access (#156), and data-room folders, documents and versions cannot point into another project (composite foreign keys, #181) | ✅ |
| Immutable audit log, stored separately, exportable | `audit` schema, insert-only grants + triggers rejecting UPDATE/DELETE/TRUNCATE; search API indexed by user, object and action; CSV export streamed in batches (#116) | ✅ |
| Encryption in transit (TLS 1.2+) and at rest (AES-256) | TLS at Kong/ingress, `sslmode=verify-full`; outgoing mail with STARTTLS required (no plaintext fallback), TLS 1.2/1.3 and server identity checked when `SMTP_STARTTLS=true` (#127); customer-managed KMS keys (rotated) for S3 documents, RDS and log groups; encrypted ElastiCache/OpenSearch/MQ | ✅ IaC |
| Watermark, download/print restrictions, access logging | Per-viewer watermark on preview pages and downloaded PDFs; previews are images so the file never reaches a browser that may not download it; `dr.folder.download_allowed/print_allowed` (deny by default, PDF print permission removed); views and downloads audited (bulk ZIP downloads logged before streaming); bulk downloads of 50+ documents need a sign-in with MFA within 10 min (step-up via `auth_time`); per-user mass-download detection (200 documents / 15 min) audited and alerted | ✅ |
| Malware scan on every upload, type validation, size limit | ClamAV INSTREAM + Tika magic-byte detection against an allowlist (`diligence.ingestion.allowed-content-types`); nothing is served before CLEAN; content clamd cannot inspect (encrypted archives, parts beyond its scan limits) is refused, not passed as clean (#140); 100 MB limit at Kong, Spring and clamd. The parsers (ClamAV, Tika, Gotenberg) run isolated in `diligence-processing`, and the backend's own page rendering of uploads (PDFBox) runs in the separate `diligence-ingestion` worker, not in the API pods (#162): no egress, non-root, read-only, pinned images ([processing README](infrastructure/kubernetes/processing/README.md), #92) | ✅ |
| Outbound webhooks (ADM-07) | HTTPS-only targets that must resolve to public addresses (checked on save and before every delivery; the delivery connects only to the addresses checked, so DNS rebinding is refused; no redirects; receiver bodies never echoed: SSRF, #87); backend egress on 443 is limited to public addresses plus the environment's VPC (`backend-egress`, `backend-egress-vpc-https`); every other egress rule names its destinations: data stores only in the VPC (`backend-egress-vpc-data`, `frontend-egress-vpc-redis`), SMTP only to public addresses, processing and telemetry only in their namespaces (#180); delivery logs (which keep the payload) are deleted after `WEBHOOK_LOG_RETENTION` (30 days) and at once when their project is purged (#90); HMAC-SHA256 signature with the key shown once and rotatable, stored encrypted (AES-256-GCM, key-encryption key from Vault via `WEBHOOK_KEY_ENCRYPTION_KEY`); Super Admin only (controller + RLS, migration `V202611050500`); deliveries outside DB transactions | ✅ |
| Rate limiting | Kong per client IP (600/min across all Kong proxy replicas: counters shared in the environment's Redis over TLS, Kong reads only the Redis host and password, fails open if Redis is down, #150); backend per authenticated user in Redis (600/min, 60/min for search, downloads and exports) with 429 + `Retry-After`; refusals counted and alerted (`DiligenceRateLimitedUsers`); fails open if Redis is down | ✅ |
| DAST | Nightly OWASP ZAP in CI (`.github/workflows/dast.yml`): signed-in baseline scan of the web app and active API scan from the OpenAPI spec against the full compose stack; fails on High alerts and rules marked FAIL in `tests/security/baseline.conf`; reports kept 30 days | ✅ |
| SAST | CodeQL (`.github/workflows/codeql.yml`, #88) on every push, pull request and weekly: `security-extended` queries for the backend (Java), the BFF (TypeScript) and the workflows (Actions injection); results in Security → Code scanning. New High/Critical alerts are fixed before merge or dismissed with a written reason. Third-party actions are pinned to commit SHAs (Dependabot updates them); server-side frontend logs are single-line JSON (`src/lib/log.ts`) so request values cannot forge log entries | ✅ |
| IP / location restriction, anomalous login detection | Per-project IP allow-list for external users (project settings, `ProjectNetworkConfiguration`, refusals audited as `project.access-blocked`; the project list and the portfolio leave out projects the caller's address may not reach, #125); Keycloak login event metrics + Prometheus alerts for failed-login spikes, lockouts and blocked access ([suspicious-login runbook](docs/runbooks/suspicious-login.md)) | ✅ |
| Encrypted backups, restore tests, incident response | RDS backups (14 days) + PITR, S3 versioning, KMS; [incident response plan](docs/runbooks/incident-response.md) and [backup/restore runbook](docs/runbooks/backup-restore.md) | ✅ plan; ⏳ first restore test before go-live |

## Compliance and data governance (PRD 10.2)

- **UU No. 27/2022 (PDP)**: processing basis, data subject rights, incident notification and the data protection
  officer are mapped in the compliance register (owner: Security Officer). Hosting location is an open decision
  ([ADR-0003](docs/adr/0003-hosting-and-data-residency.md)).
- **Retention**: project data is deleted after closure per client agreement (ADM-06): when an archived project passes
  its retention period every module deletes its data (data room files and search entries, findings, request items,
  Q&A, reports, notifications, timesheet, webhook logs; #114). Documents are deleted with every S3 object version
  and delete marker (the bucket is versioned), so they cannot be restored from S3; database rows remain only in the
  RDS backups until those expire (14 days). A purge that cannot delete everything fails and is retried; it is
  recorded as `project.data-purged` only once the data is gone (#124). The backend's role needs
  `s3:ListBucketVersions` and `s3:DeleteObjectVersion` on the document bucket. The project record, its workstreams and
  expired memberships stay as the record that it existed;
  the audit log is retained per policy and is not deletable by the application.
- **NDA / terms of use**: external users must accept them on first login (Keycloak `TERMS_AND_CONDITIONS` required
  action). The formal bilingual Non-Disclosure Agreement and Digital Workspace Terms of Use are configured in the
  realm file (`infrastructure/docker/keycloak/diligence-realm.json`).
- **Assessments**: independent penetration test before go-live and at least annually (scope and internal pre-test:
  [docs/security/penetration-test.md](docs/security/penetration-test.md)); ISO/IEC 27001 certification
  targeted within 12 months of go-live.

## AI principles (PRD 10.3, phase 3)

Client documents are never used to train third-party models; AI processing only through zero-retention providers or
self-hosted models; AI output is a suggestion until validated by a person and always cites its source document;
clients can disable AI per project (`prj.project.ai_features_enabled`, default off).

## Secrets management

- **Never commit secrets**: passwords, tokens, API keys, client secrets, private keys, `.env` files, `*.tfvars`,
  Terraform state. These are git-ignored and gitleaks runs in CI on every push. GitHub secret scanning with push
  protection is enabled on the repository (since 2026-11-11): a push containing a recognised provider secret is
  rejected before it reaches the history. Bypass it only for a confirmed false positive, with the reason given.
- Local development uses `.env` (placeholders only in `.env.example`).
- Deployed environments receive secrets only from **HashiCorp Vault** via the Vault Secrets Operator
  ([ADR-0002](docs/adr/0002-secrets-via-vault-operator.md)): backend (`kv/diligence/<env>/backend/*`, including
  `KEYCLOAK_ADMIN_CLIENT_SECRET`, SMTP credentials and the database logins: `DB_USERNAME`/`DB_PASSWORD` for the
  runtime role `diligence_app`, `FLYWAY_USERNAME`/`FLYWAY_PASSWORD` for the schema owner, #177) and frontend
  (`kv/diligence/<env>/frontend`: `SESSION_SECRET`, `SESSION_ENCRYPTION_KEY`, `OIDC_CLIENT_SECRET`;
  `kv/diligence/<env>/frontend-redis`: `REDIS_URL`, written by Terraform).
- Redis has one ElastiCache user per workload, restricted by key pattern (#171): only the web app reads sessions
  (`diligence:session:*`); the backend and Kong cannot (`modules/redis/access-strings.json`,
  `RedisAccessStringsIntegrationTest`).
- CI authenticates to clouds with **OIDC federation**; no long-lived cloud keys in GitHub.
- If a secret is committed: treat it as compromised, **rotate it immediately**, then remove it from history.

## Application security baseline

| Area | Requirement |
|------|-------------|
| Tokens | Never stored in the browser: the frontend is a BFF; tokens live in Redis, encrypted with AES-256-GCM under a key only the BFF holds (#172), the cookie holds a sealed session id (`HttpOnly`, `SameSite=Lax`, `Secure` on HTTPS) |
| CSRF | Server actions are origin-checked by Next.js; logout is POST + same-origin; the API accepts bearer tokens only |
| Redirects | Post-login targets must be same-origin relative paths (`safeReturnTo`) |
| Authorization | Deny by default at every layer; admin API requires `SUPER_ADMIN`; accounts without an application role (e.g. new SSO accounts) get no web session and 403 from the API until a Super Admin assigns a role (#152) |
| Request size | Uploads and imports (multipart) up to 100 MB at Kong, Spring and clamd; every other request body is capped at `HTTP_MAX_BODY_SIZE` (1 MB) by `RequestBodyLimitFilter` before authentication and parsing, chunked bodies included (413, #117) |
| Input | Bean Validation on every request DTO; problem details (RFC 9457) without stack traces |
| Headers | HSTS, `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy`; nonce-based Content-Security-Policy on pages (`src/proxy.ts`), `default-src 'none'; sandbox` on route handlers |
| Client addresses | Watermark, audit and rate-limit IPs come from our own proxies only, never from a caller's `X-Forwarded-For` ([docs/security/client-addresses.md](docs/security/client-addresses.md)) |
| CORS | Explicit origin allow-list per environment |
| Privacy | No PII in logs or Sentry; the OTel Collector strips `Authorization` and `Cookie` |

## Infrastructure baseline

- Non-root containers (UID 10001), read-only root filesystem, all capabilities dropped, `restricted` Pod Security.
- Default-deny NetworkPolicies, enforced by the VPC CNI network policy agent (`modules/kubernetes-cluster`, #157) and
  checked after deployment with `infrastructure/kubernetes/scripts/verify-network-policies.sh`; backend reachable only
  from Kong; management port only from monitoring.
- Data services are private; API docs disabled in production.
- Each workload has its own data-store credential where the service allows it: the backend's OpenSearch user may only
  use the `diligence-*` indices; the master credential is readable only by the `opensearch-bootstrap` Job (#159).
- AWS access without static keys: the backend's service account gets an EKS Pod Identity role limited to the documents
  bucket and its KMS key (`modules/workload-identity`, #158); nodes enforce IMDSv2 with one hop, so pods cannot fall
  back to the node's instance profile.
- Infrastructure logging: VPC flow logs, EKS control-plane logs, OpenSearch audit logs, S3 access logs.
- Supply chain: Dependabot, dependency review, Trivy (images + IaC), SBOM and provenance, SonarQube. Images come
  only from GHCR (trusted registry list in `.trivy/data`). Accepted scanner findings live in `.trivyignore.yaml`
  with a written justification. Images are signed keyless with cosign by the image workflow, and Kyverno admits
  only signed `ghcr.io/inpartnerhub/diligence-*` images in the Diligence namespaces (`infrastructure/kubernetes/policies`,
  ADR-0006, #167). Third-party Actions are pinned to SHAs.

## Local stack caveat

`compose.yaml` is for development only: Vault in dev mode, OpenSearch security disabled, Keycloak `start-dev`,
HTTP OIDC allowed (`OIDC_ALLOW_HTTP=true`). All ports bind to `127.0.0.1`. Never reuse this configuration elsewhere.
