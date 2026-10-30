# Database

PostgreSQL schema is owned exclusively by **Flyway**. Hibernate runs with `ddl-auto: validate` and never alters the schema.

```
database/
├── migrations/postgresql/   # Versioned Flyway migrations, packaged into the backend jar as classpath:db/migration
└── seeds/local/             # Local-only seed data (never applied outside developer machines)
```

## Conventions

| Rule | Detail |
|------|--------|
| File naming | `V<yyyyMMddHHmm>__<module>_<description>.sql` for new migrations (e.g. `V202611051030__document_create_document_table.sql`). `V1__baseline.sql` is the only exception. |
| Immutability | A merged migration is never edited. Fix forward with a new migration. |
| Module ownership | Each application module owns one PostgreSQL schema (`document`, `ingestion`, `audit`). No foreign keys across schemas. |
| Zero-downtime | Use expand → migrate → contract. Never drop or rename a column in the same release that stops using it. |
| Large tables | `CREATE INDEX CONCURRENTLY` in its own migration with `-- flyway:executeInTransaction=false`. |
| Types | `uuid` PKs, `timestamptz` for time, `text` over `varchar(n)` unless a limit is a business rule. |

## Running migrations

Migrations run automatically on backend startup. Flyway takes a PostgreSQL advisory lock, so concurrent replicas
during a rolling update are safe: one migrates, the others wait. Locally: `make backend-run` (or `docker compose up backend`).
