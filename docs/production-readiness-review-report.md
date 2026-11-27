# Production-Readiness Review — Diligence Platform

**Date:** 2026-11-27 · **Branch:** `fix/single-server-audit-hardening` · **Scope:** whole repository, with emphasis
on the single-server Docker Compose deployment (ADR-0012), which is the default production target and the least-tested
path.

## Method

Five parallel audit agents each took one dimension and reported evidence-based findings (file:line, confidence):
Security & secrets, Infrastructure & deployment, Backend runtime, CI/CD & supply chain, Frontend & operational docs.
Findings below are consolidated and triaged. Each is **Fixed** (applied in this branch), **Deferred-in-repo** (a
concrete in-repo fix is ready but needs a build/test cycle this environment can't run — no Docker daemon, no JDK), or
**Ops/Process** (organisational or external action, not a code change). One finding was **dismissed** as a false alarm.

## Verdict

The application layer is strong (BFF sessions, JWT+audience validation, RLS policy design, audit-log immutability,
SSRF defenses, CSP, ingestion resource bounds, minimal network exposure). The gaps are concentrated in the **default
single-server mode** and in **enforcement** (not mechanism) on the CI/CD side.

After this branch's fixes, the two genuinely dangerous defaults are closed: malware scanning no longer fails open, and
container logs can no longer fill the disk. The remaining go-live blockers are mostly ops/process (branch protection,
image-signature enforcement, a tested restore) plus two defense-in-depth code changes that need a build cycle.

| Severity | Fixed | Deferred-in-repo | Ops/Process | Dismissed |
|----------|:-----:|:----------------:|:-----------:|:---------:|
| Critical | 2 | — | 1 | — |
| High | 4 | 2 | 4 | 1 |
| Medium | 5 | 4 | 4 | — |
| Low/Info | 4 | — | 5 | — |

## Findings & status

| ID | Title | Severity | Status |
|----|-------|----------|--------|
| BE-01 | Default prod stack shipped with antivirus fail-**open** (`CLAMAV_MODE=external`) | Critical | **Fixed** |
| OPS-01 / INF-02 | No restore procedure for the single-server stack | Critical | **Fixed** |
| CI-01 | No enforced merge gate; self-merge on `main` is "current practice" | Critical→High | Ops/Process |
| INF-01 | No log rotation in `compose.prod.yaml` → disk fill | High | **Fixed** |
| BE-02 | No DB `statement_timeout` / `idle_in_transaction_session_timeout` | High | **Fixed** |
| BE-03 | K8s readiness probe didn't gate on DB health | High | **Fixed** |
| FE-01 | No React error boundary (built-in error page breaks CSP) | High | **Fixed** |
| SEC-01 | compose containers lack read-only rootfs / cap-drop / non-root | High | Deferred-in-repo |
| SEC-04 | Single-server backend logs in as DB owner; no `FORCE ROW LEVEL SECURITY` backstop | Medium→High | Deferred-in-repo |
| CI-02 | Deploy not gated on commit CI status; no `cosign verify` | High | Ops/Process |
| CI-03 | Image signatures produced but never verified at admission (Kyverno opt-in) | High | Ops/Process |
| CI-04 | Single-server mode has no supply-chain pipeline | High | Ops/Process |
| OPS-02 | Secret-rotation runbook was AWS/ESO-only | High | **Fixed** (compose section added) |
| OPS-03 | Core runbooks use K8s/RDS/NGINX commands | High | Partially fixed (ingestion-queue, slow-api) |
| BE-04 | "compose backend has no healthcheck → stack won't start" | High | **Dismissed** (image HEALTHCHECK satisfies it) |
| SEC-02 / INF-03 | Prod images default to mutable `:main` tag | Medium | **Fixed** (`.env.prod.example` placeholder) |
| INF-04 | No CPU/pids limits | Medium | **Fixed** (`pids_limit`; CPU documented as tunable) |
| INF-05 | Prometheus `external_labels: environment: local` leaks into prod | Low | **Fixed** |
| CI-05 | Trivy scans the `:scan` build, not the pushed image; `ignore-unfixed` | Medium | Deferred-in-repo |
| CI-06 | Trivy-ignored Guava CVE not time-boxed | Medium | **Fixed** (review date added) |
| CI-07 | No Gradle dependency verification / wrapper checksum | Medium | Deferred-in-repo |
| CI-08 | Playwright e2e suite never runs in CI | Medium | Deferred-in-repo |
| BE-05 | No correlation/trace ID in structured logs | Medium | Deferred-in-repo |
| OPS-04 | ingestion-queue runbook contradicts single-server model | Medium | **Fixed** |
| OPS-05 | Alert rules fire into nothing (no Alertmanager/Grafana in compose) | Medium | Ops/Process |
| INF-06 | App base images tag-pinned, not digest-pinned | Low | Deferred-in-repo |
| BE-06 | Webhook delivery retries permanent 4xx responses | Low | **Fixed** |
| FE-02 | HSTS downgraded at the Caddy edge (shorter max-age, no preload) | Low | **Fixed** |
| OPS-06 | `DB_POOL_SIZE` default wrong in slow-api runbook | Low | **Fixed** |
| SEC-06 | SECURITY.md infra baseline is K8s-only, read as universal | Low | **Fixed** (scoped) |
| SEC-03 | Keycloak prod redirect URIs set by a manual Admin Console step | Medium | Ops/Process |
| SEC-05 | Local `.env` holds a production-shaped OIDC secret | Low | Ops/Process |
| SEC-07 | Realm `sslRequired: external` | Info | By design (no action) |
| OPS-07 | On-call path not discoverable from README | Low | Ops/Process |

## Fixed in this branch

- **BE-01 — AV fail-open → fail-closed.** `.env.prod.example` and the `compose.prod.yaml` default are now
  `CLAMAV_MODE=required`; uploads are refused until the operator chooses in-process ClamAV (`--profile processing`,
  8 GiB preset) or verified external scanning (`external`). Comments document both safe paths. **Behavior change:** a
  fresh deployment will reject uploads until scanning is configured — this is intentional (a confidential data room
  must not silently accept unscanned files).
- **OPS-01 / INF-02 — restore for single-server.** Added a backup+restore section to `backup-restore.md` (stop writes
  → recreate roles → `gunzip | psql` for both the app and Keycloak DBs → verify) and extended
  `backup-postgres.sh` to also capture cluster roles (`pg_dumpall --roles-only`), without which a fresh-host restore
  would be missing `diligence_app`. Added an explicit RPO note (daily dump ≠ the ≤1 h target).
- **INF-01 — log rotation.** `json-file` driver capped at `max-size: 10m`, `max-file: 5` on the shared `x-hardening`
  anchor (all services).
- **INF-04 — pids limit.** `pids_limit: ${PIDS_LIMIT:-2048}` on the anchor (fork-bomb / thread-leak cap). CPU limits
  left as a documented, opt-in tunable (hard caps risk throttling the lean box and need load testing).
- **BE-02 — DB timeouts.** Hikari `connection-init-sql` sets `statement_timeout` (30s) and
  `idle_in_transaction_session_timeout` (60s), both env-tunable. Flyway uses its own datasource, so migrations are
  unaffected; long jobs (search reindex, report render, bulk export) stay under the cap by processing in bounded
  batches rather than one long statement, and `DB_STATEMENT_TIMEOUT` can be raised if a legitimate statement is cancelled.
- **BE-03 — readiness gates on DB.** `management.endpoint.health.group.readiness.include: readinessState, db`.
  Liveness stays lifecycle-only; AV/sidecar health deliberately excluded from readiness.
- **BE-06 — webhook retries.** A 4xx (except 408/429) now breaks the retry loop immediately.
- **FE-01 — error boundaries.** Added `app/global-error.tsx` (self-contained, inline styles only — CSP-safe, no
  provider) and `app/(app)/error.tsx` (translated Ant Design `Result`, reports to Sentry). Added `errorTitle` /
  `errorRetry` i18n keys to **both** en and id. Validated: `typecheck`, `lint`, and 106 unit tests pass.
- **SEC-02/INF-03 — image pinning.** `.env.prod.example` ships a non-pullable `sha-REPLACE_WITH_RELEASED_COMMIT`
  placeholder so `:main` is never run by accident.
- **FE-02 — HSTS.** Caddy edge aligned to `max-age=63072000; includeSubDomains; preload`.
- **INF-05 — Prometheus label.** Removed the hardcoded `environment: local` from the shared config.
- **OPS-02/03/04/06 — runbooks.** Added single-server sections to `secret-rotation.md`, `ingestion-queue.md`,
  `slow-api.md` (also fixed the `DB_POOL_SIZE` default).
- **SEC-06 — docs accuracy.** SECURITY.md "Infrastructure baseline" now states it describes the Kubernetes deployment
  and summarizes the compose hardening level.
- **CI-06 — CVE time-box.** Added a 2026-12-31 review date to the Guava suppression.

All config/YAML changes were validated with `docker compose -f compose.prod.yaml --env-file .env.prod.example config`
(exit 0). Frontend changes pass typecheck + lint + tests. Backend changes could not be compiled here (no JDK) but are
localized and will be built by `backend.yml` CI.

## Deferred-in-repo (fix ready; needs a build/test cycle)

These are real fixes, withheld only because they can't be validated in this environment and the downside of shipping
them unverified (a broken migration or a container that won't boot) would be worse than documenting them precisely.

### SEC-01 — compose container hardening
The app images already run this exact profile under Kubernetes (`readOnlyRootFilesystem`, `drop:[ALL]`, non-root
10001). Port it to compose for `backend` and `frontend`, using a **disk-backed** writable mount for the backend's
`/tmp` (it buffers up to `HTTP_MAX_CONCURRENT_UPLOADS × 100 MB`; a `tmpfs` there would consume the RAM budget):
```yaml
  backend:
    read_only: true
    cap_drop: [ALL]
    volumes:
      - backend-tmp:/tmp     # disk-backed named volume (not tmpfs) so upload buffering stays off the RAM budget
  frontend:
    read_only: true
    cap_drop: [ALL]
    tmpfs:
      - /app/.next/cache
# add `backend-tmp:` under top-level volumes:
```
Harden `postgres`/`redis`/`keycloak`/`caddy` **separately and after testing** — their entrypoints run as root and need
specific caps (CHOWN, SETUID, SETGID, DAC_OVERRIDE, FOWNER), so a blanket `cap_drop:[ALL]` will break them. Validate
with `docker compose up` on a staging host before merging.

### SEC-04 — RLS backstop for single-server
In single-server mode the runtime logs in as the DB owner and relies on the app's per-transaction `SET LOCAL ROLE
diligence_app`. Add a DB-level backstop so a missed role switch can't bypass RLS. **Simplest:** a new migration
forcing RLS on the policy-protected tables:
```sql
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY[
    'prj.project','prj.workstream','prj.project_member','req.request_item','req.request_item_document',
    'dr.folder','dr.document','dr.document_version','fnd.finding','fnd.finding_document','fnd.finding_question',
    'qna.question','rpt.report','rpt.report_finding'] LOOP   -- plus the adm.* tables from V202611130900
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
  END LOOP;
END $$;
```
**Run `./gradlew integrationTest` before merging:** this is harmless in normal flow (the app always switches role) but
will now also apply RLS to any test fixture or future migration that INSERTs as the owner. **Stronger alternative:**
provision a dedicated non-owner login role as the single-server runtime credential (mirroring K8s #177) and reserve the
owner credential for Flyway.

### Other deferred-in-repo
- **CI-05** — scan the pushed image by digest (`steps.push.outputs.digest`) rather than the `:scan` build, and
  reconsider `ignore-unfixed: true` for the image gate.
- **CI-07** — add Gradle dependency verification (`verification-metadata.xml`) and a `distributionSha256Sum` to
  `gradle-wrapper.properties`.
- **CI-08** — add a PR (or pre-deploy) job that runs the committed Playwright suite against a compose-up stack; the
  DAST workflow already shows the pattern.
- **BE-05** — add a high-precedence `OncePerRequestFilter` that reads/generates `X-Request-Id` (and `traceparent`),
  puts it in MDC, echoes it on the response, and include the key in the ECS log format.
- **INF-06** — digest-pin the `node:22-alpine` and `eclipse-temurin:21` base images (as already done for the infra
  images).

## Ops / Process (not code)

1. **CI-01 (top risk)** — enable a branch-protection ruleset on `main`: required status checks (backend, frontend,
   CodeQL, Security), ≥1 non-author approval, dismiss-stale-approvals; replace the placeholder `CODEOWNERS` teams.
   Every other CI guarantee is advisory until this is on.
2. **CI-03 / CI-02** — enforce image provenance before launch: apply the Kyverno policy
   (`kubectl apply -k infrastructure/kubernetes/policies`, verify the hardcoded repo subject matches) and add a
   `cosign verify` + required-checks-green precondition to `deploy.yml`.
3. **CI-04** — document the single-server release procedure with mandatory `cosign verify` of each image before
   `docker compose up`, and confirm whether launch is compose- or K8s-based so the right controls are mandatory.
4. **SEC-03** — replace the manual Keycloak Admin Console step with a prod realm overlay / `kcadm` bootstrap that sets
   the HTTPS redirect URIs and removes the committed `http://localhost:3000/*` entries.
5. **SEC-05** — confirm the local `.env` `OIDC_CLIENT_SECRET` is a throwaway; rotate it if it came from any shared
   environment. (It is git-ignored and not in history — no committed leak.)
6. **OPS-05** — either add an Alertmanager receiver (+`alerting:` block) under `--profile observability`, or document
   that single-server alert rules are advisory and the real notification path is Sentry + the uptime monitor.
7. **OPS-03 (remaining)** — add single-server command variants to the rest of the runbooks (backend-unavailable,
   database-migration-failure, keycloak-outage, suspicious-login, failed-events).
8. **OPS-07** — add a one-line pointer from the README/runbooks to where on-call is defined (the incident register).
9. **README go-live gates (unchanged)** — external penetration test, UAT sign-off, and a first real backup/**restore**
   test (now runnable for compose via the new `backup-restore.md` section).

## Dismissed

- **BE-04** — claimed the compose backend has no healthcheck so `frontend: service_healthy` can't be satisfied and the
  stack won't start. The backend **image** defines a `HEALTHCHECK` (`apps/backend/Dockerfile:30-31`), which Docker
  Compose uses to satisfy `service_healthy`. The stack starts correctly; no change needed.
