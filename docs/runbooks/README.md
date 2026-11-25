# Runbooks

One file per operational scenario: symptoms, what to check, mitigation, escalation. Alerts in
`infrastructure/docker/prometheus/alerts.yml` (and the Kubernetes `PrometheusRule`) link to the runbook that handles
them. Part of the MVP Definition of Done (PRD Bab 14): the Tech Lead and Security Officer review these before go-live
and after every incident.

| Runbook | Covers | Alerts |
|---------|--------|--------|
| [incident-response.md](incident-response.md) | Incident response plan: severity, roles, communication, UU PDP notification, post-mortem | all `critical` |
| [backend-unavailable.md](backend-unavailable.md) | API or ingress unavailable, API returning 5xx | `DiligenceBackendDown`, `DiligenceHighErrorRate` |
| [slow-api.md](slow-api.md) | Latency above the PRD targets, database pool exhaustion | `DiligenceSlowApi`, `DiligenceDatabasePoolExhausted` |
| [suspicious-login.md](suspicious-login.md) | Password spraying, lockouts, access from outside a project's IP allow-list, ID probing | `DiligenceLoginFailureSpike`, `DiligenceAccountLockouts`, `DiligenceProjectAccessBlocked`, `DiligenceForbiddenSpike` |
| [ingestion-queue.md](ingestion-queue.md) | Stuck or failed document processing jobs (`dr.ingestion_job`: scan, OCR, preview) | — |
| [malware-detected.md](malware-detected.md) | The virus scanner found malware in an upload | `DiligenceMalwareDetected` |
| [webhook-failures.md](webhook-failures.md) | Outbound webhook deliveries giving up or blocked by the target policy | `DiligenceWebhookDeliveriesFailing` |
| [failed-events.md](failed-events.md) | Module listeners failing: search indexing, notifications, webhooks, audit (automatic retries, exhausted events) | `DiligenceEventsFailing`, `DiligenceEventsRetriesExhausted` |
| [backup-restore.md](backup-restore.md) | Restoring PostgreSQL / S3, the quarterly restore test (RPO ≤ 1 h, RTO ≤ 4 h) | — |
| [database-migration-failure.md](database-migration-failure.md) | Failed Flyway migration during a rollout | — |
| [keycloak-outage.md](keycloak-outage.md) | Sign-in failing, token validation errors | `DiligenceKeycloakDown` |
| [search-reindex.md](search-reindex.md) | Search results missing or search index rebuild (PostgreSQL FTS `dr.document_search_index`) | — |
| [secret-rotation.md](secret-rotation.md) | Rotating credentials in AWS Secrets Manager / Kubernetes Secrets, webhook signing keys | — |

Contacts (on-call rotation, Security Officer, Data Protection Officer, cloud provider support) live in the
incident register owned by the Security Officer, not in this public repository.
