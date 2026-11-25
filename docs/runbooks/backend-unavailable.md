# Backend unavailable or failing

**Alerts:** `DiligenceBackendDown`, `DiligenceHighErrorRate`.

Every request to the web app and the API goes through the NGINX Ingress Controller. If routing is failing rather than
the backend itself, check the controller pods in the `ingress-nginx` namespace (`kubectl -n ingress-nginx get pods`,
logs, the last change to the Ingress) before the backend.

## Check

1. Pods: `kubectl -n diligence get pods -l app.kubernetes.io/name=diligence-backend` — CrashLoopBackOff, OOMKilled?
2. Logs: `kubectl -n diligence logs deploy/diligence-backend --since=15m` (structured JSON to stdout). Look for
   `UpstreamServiceException` (Tika, Gotenberg, ClamAV), Flyway errors, `HikariPool … Connection is not available`.
3. Dependencies: readiness probe `/actuator/health/readiness` on the management port 8081 lists PostgreSQL and Redis.
4. Recent change? `kubectl -n diligence rollout history deploy/diligence-backend`.

## Mitigate

- Bad release: `kubectl -n diligence rollout undo deploy/diligence-backend`. If the release contained a Flyway
  migration see [database-migration-failure.md](database-migration-failure.md) first — never roll back past a
  migration without checking it.
- Out of memory: raise the memory limit in the overlay, then investigate (heap dump from `-XX:+ExitOnOutOfMemoryError`
  restarts is not kept; reproduce in staging).
- Dependency down: follow that service's runbook; the API stays read-mostly available when ingestion workers fail.

Escalate to SEV-1 when it lasts more than 30 minutes in business hours (availability target 99.5%, PRD Bab 7).
