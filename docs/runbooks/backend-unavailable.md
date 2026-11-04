# Backend unavailable or failing

**Alerts:** `DiligenceBackendDown`, `DiligenceHighErrorRate`.

## Check

1. Pods: `kubectl -n diligence get pods -l app.kubernetes.io/name=diligence-backend` — CrashLoopBackOff, OOMKilled?
2. Logs: `kubectl -n diligence logs deploy/diligence-backend --since=15m` or the *diligence-backend* index in
   OpenSearch Dashboards. Look for `UpstreamServiceException` (Tika, Gotenberg, ClamAV, OpenSearch), Flyway errors,
   `HikariPool … Connection is not available`.
3. Dependencies: readiness probe `/actuator/health/readiness` on the management port 8081 lists PostgreSQL, Redis,
   RabbitMQ.
4. Recent change? `kubectl -n diligence rollout history deploy/diligence-backend`.

## Mitigate

- Bad release: `kubectl -n diligence rollout undo deploy/diligence-backend`. If the release contained a Flyway
  migration see [database-migration-failure.md](database-migration-failure.md) first — never roll back past a
  migration without checking it.
- Out of memory: raise the memory limit in the overlay, then investigate (heap dump from `-XX:+ExitOnOutOfMemoryError`
  restarts is not kept; reproduce in staging).
- Dependency down: follow that service's runbook; the API stays read-mostly available when ingestion workers fail.

Escalate to SEV-1 when it lasts more than 30 minutes in business hours (availability target 99.5%, PRD Bab 7).
