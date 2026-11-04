# Failed database migration

Flyway runs at backend start-up (`spring.flyway`, `validate-on-migrate: true`); migrations live in
`database/migrations/postgresql` and are append-only.

## Check

1. Backend logs: `FlywayException` with the failing version and SQL error.
2. `SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;`

## Mitigate

- PostgreSQL runs DDL in a transaction, so a failed migration normally leaves no partial change. Roll the deployment
  back (`kubectl rollout undo`); old pods run against the old schema.
- If a row with `success = false` remains, delete only that row after confirming the DDL was rolled back, fix the
  migration in a **new** version, and redeploy. Never edit a migration that already ran in any environment.
- If data was changed partially (non-transactional statements such as `CREATE INDEX CONCURRENTLY`), restore with
  PITR to just before the rollout ([backup-restore.md](backup-restore.md)).
