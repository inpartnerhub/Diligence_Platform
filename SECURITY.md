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
| MFA for all external users and administrators | Keycloak browser flow `browser-mfa`: conditional OTP for roles carrying `mfa_required` (`super_admin`, `client`, `target`, `observer`); `CONFIGURE_TOTP` on invitation | ✅ |
| Session expiry on inactivity | Keycloak `ssoSessionIdleTimeout` 30 min, max 10 h; access tokens 5 min; BFF session bounded by the refresh token | ✅ |
| Strong passwords, brute-force protection | Realm password policy (length 12, history 5, not username/e-mail), lockout after 5 failures | ✅ |
| Internal SSO | Microsoft Entra ID / Google brokering via `make keycloak-bootstrap` (credentials from env/Vault) | ✅ |
| Server-side RBAC, tested against cross-project access (IDOR) | `@PreAuthorize`, ABAC `AccessPolicy`, PostgreSQL RLS per project; `AdminEndpointSecurityTest`, `rowLevelSecurityEnforcesInformationBarrier` | ✅ foundation |
| Information barriers (Bab 3) | RLS: target never sees findings, client sees released findings / issued reports / own questions only | ✅ |
| Immutable audit log, stored separately, exportable | `audit` schema, insert-only grants + triggers rejecting UPDATE/DELETE/TRUNCATE; search API | ✅ (export: sprint 15–16) |
| Encryption in transit (TLS 1.2+) and at rest (AES-256) | TLS at Kong/ingress, `sslmode=verify-full`; customer-managed KMS keys (rotated) for S3 documents, RDS and log groups; encrypted ElastiCache/OpenSearch/MQ | ✅ IaC |
| Watermark, download/print restrictions, access logging | `Watermarker` port, `dr.folder.download_allowed/print_allowed` (deny by default) | ⏳ sprint 7–8 |
| Malware scan on every upload, type validation, size limit | ClamAV pipeline (Bab 8.3), 100 MB limit at Kong and Spring | ⏳ sprint 5–6 |
| IP / location restriction, anomalous login detection | Keycloak events + alerting; per-project IP allow-list | ⏳ phase 2 |
| Encrypted backups, restore tests, incident response | RDS backups (14 days), quarterly restore test, runbooks | ⏳ before go-live |

## Compliance and data governance (PRD 10.2)

- **UU No. 27/2022 (PDP)**: processing basis, data subject rights, incident notification and the data protection
  officer are mapped in the compliance register (owner: Security Officer). Hosting location is an open decision
  ([ADR-0003](docs/adr/0003-hosting-and-data-residency.md)).
- **Retention**: project data is deleted or anonymized after closure per client agreement (ADM-06, phase 2);
  the audit log is retained per policy and is not deletable by the application.
- **NDA / terms of use**: external users must accept them on first login (Keycloak `TERMS_AND_CONDITIONS` required
  action). The text in the realm file is a placeholder that must be replaced with the approved NDA before go-live.
- **Assessments**: independent penetration test before go-live and at least annually; ISO/IEC 27001 certification
  targeted within 12 months of go-live.

## AI principles (PRD 10.3, phase 3)

Client documents are never used to train third-party models; AI processing only through zero-retention providers or
self-hosted models; AI output is a suggestion until validated by a person and always cites its source document;
clients can disable AI per project (`prj.project.ai_features_enabled`, default off).

## Secrets management

- **Never commit secrets**: passwords, tokens, API keys, client secrets, private keys, `.env` files, `*.tfvars`,
  Terraform state. These are git-ignored and gitleaks runs in CI on every push.
- Local development uses `.env` (placeholders only in `.env.example`).
- Deployed environments receive secrets only from **HashiCorp Vault** via the Vault Secrets Operator
  ([ADR-0002](docs/adr/0002-secrets-via-vault-operator.md)): backend (`kv/diligence/<env>/backend/*`, including
  `KEYCLOAK_ADMIN_CLIENT_SECRET` and SMTP credentials) and frontend (`kv/diligence/<env>/frontend`: `SESSION_SECRET`,
  `REDIS_URL`).
- CI authenticates to clouds with **OIDC federation**; no long-lived cloud keys in GitHub.
- If a secret is committed: treat it as compromised, **rotate it immediately**, then remove it from history.

## Application security baseline

| Area | Requirement |
|------|-------------|
| Tokens | Never stored in the browser: the frontend is a BFF; tokens live in Redis, the cookie holds a sealed session id (`HttpOnly`, `SameSite=Lax`, `Secure` on HTTPS) |
| CSRF | Server actions are origin-checked by Next.js; logout is POST + same-origin; the API accepts bearer tokens only |
| Redirects | Post-login targets must be same-origin relative paths (`safeReturnTo`) |
| Authorization | Deny by default at every layer; admin API requires `SUPER_ADMIN` |
| Input | Bean Validation on every request DTO; problem details (RFC 9457) without stack traces |
| Headers | HSTS, `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy` |
| CORS | Explicit origin allow-list per environment |
| Privacy | No PII in logs or Sentry; the OTel Collector strips `Authorization` and `Cookie` |

## Infrastructure baseline

- Non-root containers (UID 10001), read-only root filesystem, all capabilities dropped, `restricted` Pod Security.
- Default-deny NetworkPolicies; backend reachable only from Kong; management port only from monitoring.
- Data services are private; API docs disabled in production.
- Infrastructure logging: VPC flow logs, EKS control-plane logs, OpenSearch audit logs, S3 access logs.
- Supply chain: Dependabot, dependency review, Trivy (images + IaC), SBOM and provenance, SonarQube. Images come
  only from GHCR (trusted registry list in `.trivy/data`). Accepted scanner findings live in `.trivyignore.yaml`
  with a written justification. Before production also pin Actions to SHAs, sign images (cosign) and verify at
  admission.

## Local stack caveat

`compose.yaml` is for development only: Vault in dev mode, OpenSearch security disabled, Keycloak `start-dev`,
HTTP OIDC allowed (`OIDC_ALLOW_HTTP=true`). All ports bind to `127.0.0.1`. Never reuse this configuration elsewhere.
