# Database

PostgreSQL schema is owned exclusively by **Flyway**. Hibernate runs with `ddl-auto: validate` and never alters the schema.

```
database/
├── migrations/postgresql/   # Versioned Flyway migrations, packaged into the backend jar as classpath:db/migration
└── seeds/local/             # Local-only seed data, e.g. the PRD Appendix B request-list template
```

## Conventions

| Rule | Detail |
|------|--------|
| File naming | `V<yyyyMMddHHmm>__<module>_<description>.sql` for new migrations (e.g. `V202611051030__document_create_document_table.sql`). `V1__baseline.sql` is the only exception. |
| Immutability | A merged migration is never edited. Fix forward with a new migration. |
| Module ownership | Each module owns one schema: `adm`, `audit`, `ntf`, `prj`, `req`, `dr`, `fnd`, `qna`, `rpt`. No foreign keys across schemas. |
| Row-level security | Project-scoped tables carry `project_id` and get RLS policies in the same migration (ADR-0004). Grants for the runtime role `diligence_app` come from default privileges in `V1`. |
| Audit | `audit.audit_log` is append-only (insert-only grants + triggers). Never add UPDATE/DELETE paths. |
| Zero-downtime | Use expand → migrate → contract. Never drop or rename a column in the same release that stops using it. |
| Large tables | `CREATE INDEX CONCURRENTLY` in its own migration with `-- flyway:executeInTransaction=false`. |
| Types | `uuid` PKs, `timestamptz` for time, `text` over `varchar(n)` unless a limit is a business rule. |

## Running migrations

Migrations run automatically on backend startup. Flyway takes a PostgreSQL advisory lock, so concurrent replicas
during a rolling update are safe: one migrates, the others wait. Locally: `make backend-run` (or `docker compose up backend`).
