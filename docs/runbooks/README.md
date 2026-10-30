# Runbooks

One file per operational scenario: symptoms, dashboards/queries to check, mitigation, escalation.
Planned runbooks:

- `dead-letter-queue.md` — inspecting and replaying messages in `*.dlq` queues
- `database-migration-failure.md` — failed Flyway migration during rollout
- `keycloak-outage.md` — token validation failures, JWKS caching behaviour
- `opensearch-reindex.md` — rebuilding search indices from PostgreSQL + S3
- `secret-rotation.md` — rotating credentials in Vault and verifying rollout
