# Contributing

## Workflow

1. Create a branch from `main`: `feat/<scope>-<short-description>`, `fix/...`, `chore/...`, `docs/...`.
2. Make focused commits using [Conventional Commits](https://www.conventionalcommits.org/) and reference the PRD
   requirement ID when there is one: `feat(admin): ADM-01 invite external users`,
   `fix(security): ADM-02 enforce OTP for observers`, `chore(deps): bump antd`.
   Scopes are module or area names: `admin`, `audit`, `notification`, `project`, `request`, `dataroom`,
   `ingestion`, `search`, `finding`, `qna`, `report`, `security`, `shared`, `frontend`, `database`, `infra`,
   `ci`, `docs`.
3. Run `make verify` (and `make backend-it` when touching persistence or messaging) before pushing.
4. Open a pull request using the template. CODEOWNERS are requested automatically.
5. Merge requires green CI and at least one approving review; squash-merge keeps `main` linear.

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
| Flyway migrations | `V<yyyyMMddHHmm>__<module>_<description>.sql` | `V202611051030__dataroom_add_folder_policy.sql` |
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
- Controllers: versioned under `ApiPaths.V1`, validated DTOs (records), `@Operation`/`@Tag` annotations,
  `@PreAuthorize` on every endpoint that is not open to all authenticated users.
- Errors: throw domain exceptions and map them to `ProblemDetail` in the module or `GlobalExceptionHandler`.
- Project-scoped tables carry `project_id` and get RLS policies in the same migration; extend the integration test
  for every new barrier. Never query project data outside a transaction (the RLS context is set per transaction).
- Auditable actions publish an event implementing `shared.audit.AuditableEvent`; never write to the audit table
  directly.
- Tests: unit tests by default (≥ 70% line coverage on services and domain rules, enforced by the build); `@Tag("integration")` + Testcontainers for persistence/messaging;
  `@ApplicationModuleTest` to test a module in isolation.

## Frontend guidelines

- Server components by default; add `"use client"` only where interactivity or Ant Design compound
  components need it.
- Call the backend only through `src/lib/api/client.ts` with types from the generated OpenAPI schema.
- Use Ant Design components and theme tokens; no ad-hoc colors or global CSS overrides.
- No secrets in `NEXT_PUBLIC_*` variables — they are shipped to the browser. Tokens stay server-side.
- Every user-facing string goes through `next-intl`; add keys to both `messages/id.json` and `messages/en.json`.
- Gate pages with `requireAccess(<navKey>)` and extend `permissions.ts` (plus its test) for new areas.

## API changes

- Additive changes within `/api/v1` only. Breaking changes require `/api/v2` and a deprecation period.
- After changing the API, run `make api-types` and commit the frontend changes in the same PR.

## Architecture decisions

Significant decisions (new technology, new module, boundary change, cross-cutting pattern) need an ADR in
`docs/adr` using [the template](docs/adr/0000-template.md), reviewed in the same PR.

## Security

Never commit secrets or real personal data, including in tests and fixtures. Report vulnerabilities as described in
[SECURITY.md](SECURITY.md), not in public issues.
