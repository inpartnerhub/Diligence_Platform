# Contributing

## Workflow

1. Create a branch from `main`: `feat/<scope>-<short-description>`, `fix/...`, `chore/...`, `docs/...`.
2. Make focused commits using [Conventional Commits](https://www.conventionalcommits.org/):
   `feat(document): add upload endpoint`, `fix(security): map client roles`, `chore(deps): bump antd`.
   Scopes are module or area names: `document`, `ingestion`, `search`, `security`, `audit`, `shared`,
   `frontend`, `database`, `infra`, `ci`, `docs`.
3. Run `make verify` (and `make backend-it` when touching persistence or messaging) before pushing.
4. Open a pull request using the template. CODEOWNERS are requested automatically.
5. Merge requires green CI and at least one approving review; squash-merge keeps `main` linear.

## Naming conventions

| Item | Convention | Example |
|------|-----------|---------|
| Deployables / images | `platform-<app>` | `platform-backend`, `platform-frontend` |
| Java packages | `com.example.platform.<module>[.internal]` | `com.example.platform.document.internal` |
| REST paths | `/api/v<major>/<plural-noun>`, kebab-case | `/api/v1/documents`, `/api/v1/search-results` |
| JSON fields | camelCase | `documentId` |
| Config properties | `platform.<module>.<property>`, kebab-case | `platform.object-storage.bucket` |
| Environment variables | UPPER_SNAKE_CASE | `S3_BUCKET`, `OIDC_ISSUER_URI` |
| RabbitMQ | `platform.<module>.<purpose>`, DLQ suffix `.dlq` | `platform.ingestion.document-uploaded` |
| DB schemas / tables / columns | snake_case, schema = module | `document.document_version` |
| Flyway migrations | `V<yyyyMMddHHmm>__<module>_<description>.sql` | `V202611051030__document_create_document.sql` |
| Kubernetes resources | `platform-<app>[-<purpose>]` | `platform-backend-config` |
| Terraform | snake_case resources, kebab-case module dirs | `module "object_storage"` → `modules/object-storage` |
| Keycloak roles | `platform_<role>` | `platform_admin` |
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
- Tests: unit tests by default; `@Tag("integration")` + Testcontainers for persistence/messaging;
  `@ApplicationModuleTest` to test a module in isolation.

## Frontend guidelines

- Server components by default; add `"use client"` only where interactivity or Ant Design compound
  components need it.
- Call the backend only through `src/lib/api/client.ts` with types from the generated OpenAPI schema.
- Use Ant Design components and theme tokens; no ad-hoc colors or global CSS overrides.
- No secrets in `NEXT_PUBLIC_*` variables — they are shipped to the browser.

## API changes

- Additive changes within `/api/v1` only. Breaking changes require `/api/v2` and a deprecation period.
- After changing the API, run `make api-types` and commit the frontend changes in the same PR.

## Architecture decisions

Significant decisions (new technology, new module, boundary change, cross-cutting pattern) need an ADR in
`docs/adr` using [the template](docs/adr/0000-template.md), reviewed in the same PR.

## Security

Never commit secrets or real personal data, including in tests and fixtures. Report vulnerabilities as described in
[SECURITY.md](SECURITY.md), not in public issues.
