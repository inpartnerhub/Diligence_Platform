# Contributing

## Workflow

One issue, one branch, one pull request.

1. Create a branch from `origin/main`: `feat/<issue>-<short-description>`, `fix/...`, `chore/...`, `docs/...`
   (e.g. `fix/152-refuse-roleless-tokens`).
2. Make focused commits using [Conventional Commits](https://www.conventionalcommits.org/) and end the subject with
   the issue number: `fix(security): refuse accounts without an application role (#152)`. Mention the PRD requirement
   ID when there is one (`feat(admin): ADM-01 invite external users (#12)`).
   Scopes are module or area names: `admin`, `audit`, `notification`, `project`, `request`, `dataroom`,
   `ingestion`, `search`, `finding`, `qna`, `report`, `security`, `shared`, `frontend`, `database`, `infra`,
   `ops`, `ci`, `deps`, `e2e`, `performance`, `docs`.
3. Before pushing run `make verify`; also `make backend-it` when the change touches persistence, migrations,
   messaging, security filters or anything a Testcontainers test covers. Report the commands and results in the PR;
   never claim a check that was not run.
4. Open a pull request with `Closes #<issue>` and the sections of the template (what changed, tests actually run,
   steps needed outside the repository).
5. Review and merge (current practice): the author reviews the diff and the CI results, fixes CodeQL/Trivy alerts and
   review comments, and squash-merges once CI is green. The squash subject is the PR title plus `(#<PR>)`; close the
   issue with a short summary of what changed and what is still to do outside the repository (deployments, realm or
   Vault changes, legal follow-ups).

`main` is not branch-protected and `.github/CODEOWNERS` still holds placeholder teams. Before go-live: protect `main`
(required status checks, at least one approving review from someone other than the author) and replace the
placeholders with real teams; this section then changes to "merge requires an approving review".

### Pre-commit hook and `--no-verify`

The workstation-wide secret hook matches field names and placeholders (`password: ${DB_PASSWORD}`, `accessToken:`,
`id-token: write`) anywhere in a staged file. When it blocks a commit, scan the staged diff with the project's own
gitleaks configuration first:

```bash
git diff --cached | docker run --rm -i -v "$PWD/.gitleaks.toml":/cfg.toml ghcr.io/gitleaks/gitleaks:v8.30.1 stdin --config /cfg.toml --no-banner
```

Only when it reports no leaks and the hook's hits are placeholders or field names may the commit use `--no-verify`;
say so in the pull request. A real secret is never committed: rotate it if it was.

## Naming conventions

| Item | Convention | Example |
|------|-----------|---------|
| Deployables / images | `diligence-<app>` | `diligence-backend`, `diligence-frontend` |
| Java packages | `com.inpartnerhub.diligence.<module>[.internal]` | `com.inpartnerhub.diligence.dataroom.internal` |
| REST paths | `/api/v<major>/<plural-noun>`, kebab-case | `/api/v1/documents`, `/api/v1/search-results` |
| JSON fields | camelCase | `documentId` |
| Config properties | `diligence.<area>.<property>`, kebab-case; the area is a module or a cross-cutting concern | `diligence.object-storage.bucket`, `diligence.step-up.max-age` |
| Environment variables | UPPER_SNAKE_CASE | `S3_BUCKET`, `OIDC_ISSUER_URI` |
| RabbitMQ | `diligence.<module>.<purpose>`, DLQ suffix `.dlq`; one dead-letter exchange `diligence.dlx`; names in `shared.messaging.MessagingTopology` | `diligence.ingestion.document-uploaded` |
| DB schemas / tables / columns | snake_case; one short schema per module (`adm` admin, `audit`, `ntf` notification, `prj` project, `req` request, `dr` data room, `fnd` finding, `qna`, `rpt` report); `public` only for the Modulith outbox and ShedLock | `dr.document_version`, `fnd.finding` |
| Flyway migrations | `V<yyyyMMddHHmm>__<description>.sql`, snake_case; start the description with the module when it fits (about half of the existing ones do). `V1`…`V19` are the original baseline | `V202611131000__project_member_organization_check.sql` |
| Kubernetes resources | Workloads and their config/secrets `diligence-<app>[-<purpose>]`; NetworkPolicies `<workload>-<ingress\|egress>[-<target>]` (plus `default-deny-all`, `allow-dns-egress`); one-off Jobs and their own resources named after the Job | `diligence-backend-config`, `backend-egress-vpc-https`, `opensearch-bootstrap` |
| Terraform | snake_case resources, kebab-case module dirs | `module "object_storage"` → `modules/object-storage` |
| Keycloak realm roles | lower-case `Role` enum name | `project_manager` ↔ `Role.PROJECT_MANAGER` |
| i18n message keys | `<area>.<key>` in `messages/{id,en}.json` (both files, same keys) | `users.inviteTitle` |
| Frontend | `PascalCase.tsx` components, `useCamelCase.tsx` hooks, `camelCase.ts` modules, `*.test.ts` next to the code; features in `src/features/<feature>` (`api.ts`, `actions.ts`, `types.ts`, `*View.tsx`) | `UsersView.tsx`, `useStepUp.tsx` |

## Backend guidelines

- Put code in the module that owns the concept. Public types go in the module root package; everything else in
  `internal`. `ModularityTests` fails the build on boundary violations or cycles.
- Prefer domain events over direct calls between modules. Other modules react with `@ApplicationModuleListener`
  (asynchronous, after commit, in its own transaction without a user: the RLS `system` context). Inside a module,
  `@TransactionalEventListener` is used for after-commit housekeeping (cache eviction) and `@EventListener` where the
  reaction must run in the caller's transaction (login bookkeeping). The upload pipeline is the only `@RabbitListener`.
- No `ddl-auto` schema changes; every schema change is a Flyway migration in `database/migrations/postgresql`,
  backward compatible with the previous release.
- A new migration's version must sort **after every migration already on `main`** (Flyway refuses an older version
  on a database that has applied newer ones). Use the current time, check `ls database/migrations/postgresql` before
  committing, and never edit or rename a merged migration — add a new one.
- Controllers: versioned under `ApiPaths.V1` (`AdminApiPaths` for `/api/v1/admin`), `@Valid` request records,
  `@Operation`/`@Tag` on every endpoint.
- Authorization, deny by default:
  - every request needs a token with an application role (`ApplicationRoleFilter`) from an enabled account
    (`DisabledAccountFilter`);
  - global-role rules use `@PreAuthorize` (about a third of the endpoints: administration, templates, time entries,
    creation of projects…);
  - project-scoped endpoints go through the module's access component (`ProjectAccess`, `DataRoomAccess`,
    `FindingAccess`, `RequestAccess`, …): it loads the caller's roles in the project and refuses what they may not do.
    Never rely on row-level security alone — a missing application check is how #152 exposed every project;
  - sensitive actions add `@RequiresRecentSignIn` (step-up with OTP).
- Errors: throw the shared exceptions (`ResourceNotFoundException`, `InvalidRequestException`,
  `ResourceConflictException`, `UpstreamServiceException`, `StepUpRequiredException`, Spring's
  `AccessDeniedException`); `GlobalExceptionHandler` maps them to RFC 9457 problem details with the types in
  `ProblemTypes`. Filters that answer themselves write the same `application/problem+json` shape.
- Project-scoped tables carry `project_id` and get RLS policies in the same migration; extend the integration test
  for every new barrier. Identity tables (`adm.*`) are row-level secured too. Never query project data outside a
  transaction (the RLS context is set per transaction).
- Auditable actions publish an event implementing `shared.audit.AuditableEvent`; never write to the audit table
  directly.
- Tests: unit tests by default (≥ 70% line coverage on services and domain rules, enforced by the build);
  `@Tag("integration")` + Testcontainers for persistence, messaging, row-level security and the security filter
  chain. An asynchronous listener (`@ApplicationModuleListener`) is checked with `await()`, not right after the call.

## Frontend guidelines

- Pages are server components; views with Ant Design interactivity are client components (`"use client"`, the
  `*View.tsx` files).
- Server code calls the backend only through `apiFetch` (`src/lib/api/client.ts`): reads in `features/<f>/api.ts`,
  mutations as server actions in `features/<f>/actions.ts`. The browser never calls the backend: uploads, downloads and
  exports go through route handlers under `src/app/api/` that add the token and stream.
- DTO types live in `src/features/<feature>/types.ts` and mirror the backend records by hand until
  `npm run generate:api` (`make api-types`) becomes part of the build.
- Use Ant Design components and the theme tokens in `src/theme/themeConfig.ts`; no global CSS overrides. A few views
  still use literal colours (`PortfolioDashboardView`, `WebhooksView`, `RequestItemDrawer`): replace them with tokens
  when touching those files, and add no new ones.
- `NEXT_PUBLIC_*` holds only non-secret values (today `NEXT_PUBLIC_SENTRY_DSN`, `NEXT_PUBLIC_APP_ENVIRONMENT`); tokens
  stay server-side in the Redis session.
- Every user-facing string goes through `next-intl`; add keys to both `messages/id.json` and `messages/en.json` (the
  two files have the same keys).
- Gate pages with `requireAccess(<navKey>)` (on the page or on the area's `layout.tsx`, like `admin/layout.tsx`) and
  extend `permissions.ts` (plus its test) for new areas. This is navigation convenience; the backend authorizes.

## API changes

- Additive changes within `/api/v1` only. Breaking changes require `/api/v2` and a deprecation period.
- After changing the API, update the matching `src/features/<feature>/types.ts` (and the frontend code using it) in
  the same PR.

## Architecture decisions and documentation

Significant decisions (new technology, new module, boundary change, cross-cutting pattern) need an ADR in
`docs/adr` using [the template](docs/adr/0000-template.md), reviewed in the same PR. Open decisions are tracked as
issues (e.g. hosting and data residency, ADR-0003).

Keep the documentation in step with the code in the same PR: `ARCHITECTURE.md` (modules, flows), `SECURITY.md`
(controls and their status), `docs/guides` (user-visible behaviour, e.g. the administrator guide), `docs/runbooks`
(operations, rotation, restore) and the README files next to infrastructure code.

## Dependencies

Dependabot opens the dependency updates (`chore(deps): bump …`); review the changelog for breaking or security
changes and merge on green CI. Security overrides of Spring Boot-managed versions live at the top of
`apps/backend/build.gradle.kts` with the CVE they fix; remove them once the BOM catches up. Third-party GitHub Actions
are pinned to commit SHAs.

## Identity (Keycloak realm)

Realm changes go into `infrastructure/docker/keycloak/diligence-realm.json` (and `keycloak-bootstrap.sh` for things
created at runtime, such as identity providers). Verify them against a running Keycloak, not only by reading the JSON:
locally `make keycloak-reset` re-imports the realm. Deployed realms do not re-import the file: list the realm changes
in the pull request and apply them to every environment in the same release as the code that relies on them (step-up
and MFA changes refuse users otherwise).

## Personal data (UU PDP)

The platform holds confidential due-diligence documents and personal data of users and of people named in documents.

- Tests, fixtures, seeds and screenshots use synthetic data only (`example.com`/`.test` addresses, generated names).
- A change that adds a category of personal data, changes how long it is kept, sends it to a new service (a
  subprocessor) or to another region says so in the pull request, so the processing register and the hosting decision
  (ADR-0003) are updated before it is released.
- No personal data in logs, Sentry or metrics labels.

## Security

Never commit secrets or real personal data, including in tests and fixtures.

- A vulnerability that is not fixed yet and could be exploited is reported privately (GitHub Security Advisory, see
  [SECURITY.md](SECURITY.md)), never in a public issue. Its public fix may be tracked as a neutral hardening issue.
- Hardening, defence-in-depth and infrastructure issues without an exploitable path may be public.
- Fix new CodeQL and Trivy alerts on a pull request before merging, or dismiss them with a written reason.
