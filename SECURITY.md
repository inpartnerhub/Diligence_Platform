# Security Policy

## Reporting a vulnerability

**Do not open a public issue.** Report suspected vulnerabilities privately through GitHub's
"Report a vulnerability" (Security → Advisories) on this repository, or to the security team contact defined by
your organization. Include affected component, version/commit, reproduction steps and impact.

| Stage | Target |
|-------|--------|
| Acknowledgement | 2 business days |
| Triage and severity (CVSS) | 5 business days |
| Fix for Critical / High | 7 / 30 days |

## Supported versions

Only the latest release deployed from `main` receives security fixes.

## Secrets management

- **Never commit secrets**: passwords, tokens, API keys, private keys, connection strings with credentials,
  `.env` files, `*.tfvars`, Terraform state. These patterns are git-ignored and gitleaks runs in CI on every push.
- Local development uses `.env` (from `.env.example`, placeholders only).
- Deployed environments receive secrets exclusively from **HashiCorp Vault**, synced by the Vault Secrets Operator.
  Terraform writes generated infrastructure credentials to Vault; the RDS master password is managed by AWS.
- CI authenticates to clouds with **OIDC federation** (`id-token: write`); no long-lived cloud keys in GitHub.
- If a secret is committed: treat it as compromised, **rotate it immediately**, then remove it from history.

## Application security baseline

| Area | Requirement |
|------|-------------|
| Authentication | Keycloak OIDC; Authorization Code + PKCE for the browser; backend validates signature, issuer and audience of every token |
| Authorization | Deny by default. RBAC with `@PreAuthorize`; resource-level decisions through ABAC `AccessPolicy` beans |
| Input | Bean Validation on every request DTO; size limits at Kong and Spring (100 MB uploads) |
| Uploads | Every file is scanned by ClamAV before extraction, indexing or download; infected files are quarantined |
| Errors | RFC 9457 problem details; no stack traces or internal messages in responses |
| Transport | TLS everywhere outside the local stack; HSTS on the frontend; S3 bucket denies non-TLS access |
| Headers | Frontend sets HSTS, `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy` |
| CORS | Explicit origin allow-list per environment (`CORS_ALLOWED_ORIGINS`) |
| Data | PII is never logged or sent to Sentry (`send-default-pii: false`, frontend `dataCollection` off); the OTel Collector strips `Authorization` and `Cookie` |
| Data at rest | Encrypted RDS, ElastiCache, OpenSearch, S3 (KMS), Amazon MQ |

## Infrastructure security baseline

- Containers run as non-root (UID 10001) with a read-only root filesystem and all Linux capabilities dropped.
- Namespaces enforce the `restricted` Pod Security Standard.
- Kubernetes NetworkPolicies are default-deny; the backend accepts traffic only from Kong, and its management
  port (8081) only from the monitoring namespace. Actuator endpoints other than health require `ROLE_PLATFORM_OPERATOR`.
- Data services are private (no public endpoints) and reachable only from cluster node security groups.
- Swagger UI / API docs are disabled in production (`API_DOCS_ENABLED=false`).

## Supply chain

- Dependabot for Gradle, npm, Docker, Compose, Terraform and GitHub Actions.
- Dependency review blocks PRs that add dependencies with known High/Critical vulnerabilities.
- Trivy scans images (blocking on fixable High/Critical) and IaC/filesystem (reported to code scanning).
- Images are published with SBOM and SLSA provenance attestations and deployed by immutable tag.
- Recommended hardening before production: pin GitHub Actions to commit SHAs, sign images (cosign) and verify
  signatures at admission, enable branch protection with required reviews and status checks.

## Local stack caveat

`compose.yaml` is for development only: Vault runs in dev mode, OpenSearch security is disabled, Keycloak runs
`start-dev`. All ports bind to `127.0.0.1`. Never expose it to a network or reuse its configuration elsewhere.
