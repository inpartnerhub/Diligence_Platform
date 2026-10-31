# Penetration testing (Sprint 15–16)

PRD Bab 10.1 and Bab 12.1 ask for an independent penetration test before go-live and at least once a year after. This
page covers two things. First, the scope handed to the external tester. Second, the register of what the internal
pre-test found and fixed, so the external test starts from a known baseline.

## 1. Scope for the external test

| Item | In scope |
|------|----------|
| Web application | `app.<env>` (Next.js BFF): every page, server action and `/api/*` route handler, the `/auth/*` flow |
| API | `api.<env>/api/v1/*` directly, with tokens for each of the seven roles (the OIDC client is public, so users can obtain their own tokens) |
| Identity | Keycloak realm `diligence`: login, MFA, lockout, password reset, invitation links, NDA required action |
| Files | Upload (malware, type confusion, archive bombs, oversized files), preview, watermarked download, report and export downloads |
| Infrastructure | Kong configuration, TLS, security headers; Kubernetes namespace from the outside only |
| Out of scope | Denial of service against shared infrastructure, social engineering, the cloud provider's control plane |

**Environment.** Staging, switched to production settings for the test: `API_DOCS_ENABLED=false` and `TRUSTED_PROXY_HOPS=1`. Seed two projects
with users in every role. The tester gets accounts per role in **both** projects, plus one account that belongs to
neither.

**Priority test cases** (from PRD Bab 3 and Bab 10):

1. Information barriers: the target never reads findings, reports or internal notes. The client never reads the data
   room, unreleased findings, unapproved questions or answers before the question is closed.
2. Cross-project access (IDOR) on every object ID: folders, documents, versions, pages, findings, request items,
   questions, reports, members and notifications.
3. Workflow bypasses:
   - releasing a finding without approval;
   - self-approval (four eyes);
   - editing released findings or issued reports;
   - a target closing its own request items.
4. Download control:
   - downloading from folders where downloads are not allowed;
   - removing or forging the watermark (name, e-mail, IP, time);
   - printing a PDF that may not be printed.
5. Session handling:
   - fixation, logout and idle expiry (30 min);
   - refresh after a role change or after the account is disabled;
   - CSRF on server actions and on the upload and logout routes.
6. Audit integrity: actions that leave no audit entry, or that record a forged IP address.

**Reporting.** CVSS 3.1 per finding, with reproduction steps. Fix times follow SECURITY.md: Critical 7 days, High
30 days. Re-test after the fixes.

## 2. Internal pre-test

Method:
- code review of every controller and service against the OWASP ASVS 4.0 level 2 areas listed in section 3;
- new automated tests;
- a ZAP baseline scan against the local stack (`make zap-baseline`).

### Findings

| ID | Severity | Finding | Fix | Test |
|----|----------|---------|-----|------|
| PT-01 | High | **Forged IP address in watermarks and the audit log.** The BFF took the left-most `X-Forwarded-For` entry, which the browser controls. Next.js keeps a browser-sent header as it is. The backend (Spring `framework` strategy) also used the left-most entry, including on direct API calls. A user could put any address on a downloaded document and in the audit trail. | The BFF reads the chain from the right (`TRUSTED_PROXY_HOPS`). The backend uses Tomcat `RemoteIpValve` (`forward-headers-strategy: native`). Kong `trusted_ips`/`real_ip_*` are configured. See [client-addresses.md](client-addresses.md). | `clientAddress.test.ts`, `ClientAddressIntegrationTest` |
| PT-02 | Medium | **API rate limit shared by all users.** Every browser request reaches the API through the BFF, so Kong's `limit_by: ip` counted the frontend pods' address: 600 requests/minute for everyone together. Kong also replaced `X-Forwarded-For` with the pod address, so in Kubernetes every watermark showed the pod's IP. | Kong trusts our proxy ranges and resolves the real client address. The settings are in compose and documented for the Kong release. | Manual (`docker compose`), [client-addresses.md](client-addresses.md) |
| PT-03 | Medium | **No Content-Security-Policy.** An XSS bug anywhere would run with the user's session. | A nonce-based CSP for every page (`script-src 'nonce-…' 'strict-dynamic'`, `object-src 'none'`, `base-uri 'none'`, `frame-ancestors 'none'`, `form-action` limited to the app and Keycloak). Route handlers get `default-src 'none'; sandbox`. | `csp.test.ts`, browser check on the local stack |
| PT-04 | Low | **Dot segments in BFF paths.** `encodeURIComponent` leaves `.` and `..` unchanged. An ID of `..` in a route parameter or server-action argument resolved to a different backend endpoint, still under the user's own token. | `backendUrl` rejects dot segments; the route handlers answer 400. | `backendUrl.test.ts` |
| PT-05 | Low | **Failed login bookkeeping broke requests.** Just-in-time provisioning runs inside the security filter. When it failed, the request returned 500: for example, a Keycloak user recreated with the same e-mail and a new subject hit `user_account_email_uq`. This happened on the first request of every 15-minute throttle window, the account was never provisioned and the login was not audited. | The filter logs the failure and lets the request through: the token already authenticated the caller, and every resource is authorized separately. Provisioning never adopts an account that has the same e-mail, because anyone able to create an identity with that address (SSO brokering included) would inherit its project memberships. It logs both IDs and still audits the login. An administrator re-links the account deliberately. | `UserAuthenticationEventFilterTest`, `UserProvisioningServiceTest` |
| PT-06 | Low | **Archiving revoked external access by the application clock.** `archive()` set the client's and target's `access_expires_at` from the JVM clock, while row-level security compares it with PostgreSQL `now()`. With the database clock behind the application, an archived project stayed visible to them for the difference. This was found through an intermittent failure of `ProjectIntegrationTest`. | The expiry is a bulk update at `current_timestamp`, so the same clock writes and checks it. Access that already ended is left as it is. | `ProjectIntegrationTest` (checked against the database clock), `ProjectServiceTest` |

### ZAP baseline (local stack, not signed in)

The first run flagged the not-found page for file-like paths (`/robots.txt`, `/sitemap.xml`): `src/proxy.ts` skipped
them, so they rendered without a CSP. The proxy now covers every path except auth, API and build assets. Everything
else came from the Keycloak login page the scan was redirected to, or was noise:

| Alert | Source | Decision |
|-------|--------|----------|
| Cookie without HttpOnly (`KC_AUTH_SESSION_HASH`), `SameSite=None` (`AUTH_SESSION_ID`, `KC_RESTART`) | Keycloak | By design: Keycloak's login scripts read the hash, and the session cookies must work across sites. They hold no application session. |
| CSP `unsafe-inline` / missing fallbacks, missing Permissions-Policy, no anti-CSRF token on the login form | Keycloak login page | Keycloak's own headers. Tightening `browserSecurityHeaders` in the realm is an option for the external test. The login form is protected by Keycloak's session code. |
| Suspicious comments, non-storable content, Spectre isolation, "modern web application" | Next.js bundles and responses | Informational or false positives. Responses are `no-store` on purpose. |

### Checked without findings

| Area | Result |
|------|--------|
| Object-level authorization | Every lookup by ID is bound to the project in the path (`findById(...).filter(projectId)`), and documents are also bound to the caller's visible folders. RLS repeats the check in the database. Now covered end to end by `CrossProjectAccessIntegrationTest` (member of both projects, and outsider). |
| Function-level authorization | Admin, audit, template and risk-matrix endpoints carry `@PreAuthorize`. Project endpoints check roles in services (`*Access`) and in RLS. |
| File names and storage keys | Names are stripped of separators and control characters, and dot-only names are rejected. Object keys contain only IDs. `Content-Disposition` is built by Spring (RFC 6266). |
| Upload types | Magic-byte detection against an allowlist that has no HTML, SVG or XML types. Nothing is served before a clean scan. 100 MB limit at Kong, Spring and clamd. |
| Spreadsheet exports | Values are written as string cells (Apache POI), so formula injection does not apply. There is no CSV export. |
| CSRF | Server actions are origin-checked by Next.js. Upload and logout check `Origin`. The cookie is `SameSite=Lax`. The API accepts bearer tokens only. |
| Error handling | `include-stacktrace: never`, `include-message: never`, RFC 9457 problem details. |
| Actuator and API docs | Actuator runs on a separate management port and requires `SUPER_ADMIN` on the API port. API docs are off in production (`API_DOCS_ENABLED`). |

### Open items for the external test

- The web ingress (`app.*`) has no rate limit. Login brute force is handled by Keycloak's lockout. Check whether
  server actions need their own limit.
- Users behind one corporate NAT share one per-IP bucket. Tune the limit from the load tests.
- `style-src 'unsafe-inline'` remains, because Ant Design injects its CSS at runtime.
