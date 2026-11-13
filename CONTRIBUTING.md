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
| Config properties | `diligence.<module>.<property>`, kebab-case | `diligence.object-storage.bucket` |
| Environment variables | UPPER_SNAKE_CASE | `S3_BUCKET`, `OIDC_ISSUER_URI` |
| RabbitMQ | `diligence.<module>.<purpose>`, DLQ suffix `.dlq` | `diligence.ingestion.document-uploaded` |
| DB schemas / tables / columns | snake_case, one schema per module | `dr.document_version`, `fnd.finding` |
| Flyway migrations | `V<yyyyMMddHHmm>__<module>_<description>.sql` (module prefix recommended, see below) | `V202611051030__dataroom_add_folder_policy.sql` |
| Kubernetes resources | `diligence-<app>[-<purpose>]` | `diligence-backend-config` |
| Terraform | snake_case resources, kebab-case module dirs | `module "object_storage"` → `modules/object-storage` |
| Keycloak realm roles | lower-case `Role` enum name | `project_manager` ↔ `Role.PROJECT_MANAGER` |
| i18n message keys | `<area>.<key>` in `messages/{id,en}.json` (both files, same keys) | `users.inviteTitle` |
| Frontend | `PascalCase.tsx` components, `camelCase.ts` modules, features in `src/features/<feature>` | |

## Backend guidelines

- Put code in the module that owns the concept. Public types go in the module root package; everything else in
  `internal`. `ModularityTests` fails the build on boundary violations or cycles.
- Prefer domain events over direct calls between modules. Listen with `@ApplicationModuleListener`.
- No `ddl-auto` schema changes; every schema change is a Flyway migration in `database/migrations/postgresql`,
  backward compatible with the previous release.
- A new migration's version must sort **after every migration already on `main`** (Flyway refuses an older version
  on a database that has applied newer ones). Use the current time, check `ls database/migrations/postgresql` before
  committing, and never edit or rename a merged migration — add a new one.
- Controllers: versioned under `ApiPaths.V1`, validated DTOs (records), `@Operation`/`@Tag` annotations,
  `@PreAuthorize` on every endpoint that is not open to all authenticated users.
- Errors: throw domain exceptions and map them to `ProblemDetail` in the module or `GlobalExceptionHandler`.
- Project-scoped tables carry `project_id` and get RLS policies in the same migration; extend the integration test
  for every new barrier. Never query project data outside a transaction (the RLS context is set per transaction).
- Auditable actions publish an event implementing `shared.audit.AuditableEvent`; never write to the audit table
  directly.
- Tests: unit tests by default (≥ 70% line coverage on services and domain rules, enforced by the build);
  `@Tag("integration")` + Testcontainers for persistence, messaging, row-level security and the security filter
  chain. An asynchronous listener (`@ApplicationModuleListener`) is checked with `await()`, not right after the call.

## Frontend guidelines

- Server components by default; add `"use client"` only where interactivity or Ant Design compound
  components need it.
- Call the backend only through `apiFetch` (`src/lib/api/client.ts`). DTO types live in `src/features/<feature>/types.ts`
  and mirror the backend records until `npm run generate:api` (`make api-types`) becomes part of the build.
- Use Ant Design components and theme tokens; no ad-hoc colors or global CSS overrides.
- No secrets in `NEXT_PUBLIC_*` variables — they are shipped to the browser. Tokens stay server-side.
- Every user-facing string goes through `next-intl`; add keys to both `messages/id.json` and `messages/en.json`.
- Gate pages with `requireAccess(<navKey>)` and extend `permissions.ts` (plus its test) for new areas.

## API changes

- Additive changes within `/api/v1` only. Breaking changes require `/api/v2` and a deprecation period.
- After changing the API, update the matching `src/features/<feature>/types.ts` (and the frontend code using it) in
  the same PR.

## Architecture decisions

Significant decisions (new technology, new module, boundary change, cross-cutting pattern) need an ADR in
`docs/adr` using [the template](docs/adr/0000-template.md), reviewed in the same PR.

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
