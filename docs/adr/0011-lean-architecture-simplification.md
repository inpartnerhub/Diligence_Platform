# ADR-0011: Lean architecture simplification for launch

- **Status:** Accepted
- **Date:** 2026-11-25
- **Deciders:** Tech Lead / Architect, Security Officer, platform owner

## Context

Prior ADRs accumulated significant operational footprint ahead of initial launch: dual-cloud hosting across Microsoft Azure Indonesia Central and AWS `ap-southeast-3` ([ADR-0003](0003-hosting-and-data-residency.md), [ADR-0007](0007-container-platform.md)), self-hosted HashiCorp Vault clusters ([ADR-0002](0002-secrets-via-vault-operator.md), [ADR-0010](0010-vault-topology.md)), RabbitMQ, OpenSearch + OpenSearch Dashboards, Kong API Gateway, OpenTelemetry Collector, always-on Kyverno admission webhooks ([ADR-0006](0006-image-signing-and-admission.md)), and multi-replica HA defaults ([ADR-0009](0009-production-keycloak.md)).

For a 50–100 user consulting firm workload at initial go-live, operating that stack imposed unnecessary infrastructure cost, local Docker VM memory pressure (> 6–8 GiB), and CI/CD and cluster-ops overhead for a small engineering team, while leaving an ADR-0007 branch collision between the AWS EKS reference implementation and the unbuilt Azure AKS decision.

We simplify the runtime, infrastructure, and delivery pipeline to a lean single-cloud AWS footprint and PostgreSQL-native queuing/search while preserving every PRD security control and maintaining clean extraction boundaries.

## Decision

1. **Single cloud (AWS `ap-southeast-3` Jakarta) with a `high_availability` toggle** *(partially supersedes [ADR-0003](0003-hosting-and-data-residency.md), supersedes [ADR-0007](0007-container-platform.md), updates [ADR-0009](0009-production-keycloak.md))*:
   - Standardize on **AWS EKS** in `ap-southeast-3` (Jakarta) with managed RDS PostgreSQL, ElastiCache Valkey, and S3, keeping all client data in Indonesia for UU PDP compliance and resolving the ADR-0007 branch collision. Multi-cloud DR to Azure is deferred.
   - Expose a Terraform `high_availability` toggle (default `false` for lean launch: Single-AZ RDS, single NAT gateway, lean EKS node sizing, and 1 Keycloak replica). Setting `high_availability = true` enables Multi-AZ RDS, redundant NAT gateways, and 2+ Keycloak replicas.

2. **RabbitMQ → PostgreSQL `dr.ingestion_job` queue**:
   - Replace RabbitMQ and AMQP externalization with a transactional job queue table (`dr.ingestion_job`) in PostgreSQL polled via `SELECT ... FOR UPDATE SKIP LOCKED`.
   - Enqueueing happens inside the same database transaction as `dr.document_version` creation, eliminating dual-write drift and broker operations. In Kubernetes, the dedicated `diligence-ingestion` Deployment (`INGESTION_LISTENER_ENABLED=true`, no Service) continues isolating untrusted document parsing from API pods.

3. **OpenSearch → PostgreSQL `tsvector` full-text search (`dr.document_search_index`)**:
   - Replace OpenSearch with PostgreSQL native full-text search backed by `dr.document_search_index` (`tsvector` column + GIN index).
   - Search queries execute inside the caller's RLS-enforced transaction, eliminating separate cluster management, JVM heap pressure, and cross-store index drift while guaranteeing folder-level information barriers at the database layer.

4. **HashiCorp Vault → AWS Secrets Manager + External Secrets Operator** *(supersedes [ADR-0002](0002-secrets-via-vault-operator.md) and [ADR-0010](0010-vault-topology.md))*:
   - Replace Vault and Vault Secrets Operator with **AWS Secrets Manager** in `ap-southeast-3` synced into Kubernetes `Secret`s by **External Secrets Operator (ESO)** authenticated via EKS Pod Identity.
   - Application containers continue reading configuration strictly from environment variables (12-factor), with no cloud SDK secret dependency in application code.

5. **Kong API Gateway → NGINX Ingress Controller**:
   - Replace Kong (and its Redis-backed rate-limit counter dependency) with **NGINX Ingress Controller** in Kubernetes for TLS termination, path/host routing, and upload body limits.
   - Locally, the Next.js BFF calls the Spring Boot backend directly without a gateway proxy container.

6. **Lean processing, rate-limiting, observability, CI, and opt-in Kyverno** *(partially supersedes [ADR-0006](0006-image-signing-and-admission.md))*:
   - **Processing & rate limiting:** Keep ClamAV, Apache Tika (+ Tesseract OCR), and Gotenberg with lean defaults; rely on backend per-user rate limiting (Redis-backed) and ingress limits rather than Kong plugins.
   - **Observability:** Retain Prometheus + Grafana (metrics and alerting), Sentry (error tracking with `send-default-pii: false`), and structured JSON logs to stdout. Remove OpenTelemetry Collector and OpenSearch Dashboards for launch.
   - **CI/CD:** Remove SonarQube analysis and `fetch-depth: 0` from `backend.yml` and `frontend.yml` (retaining Gradle/npm test & coverage gates, CodeQL, Trivy, and gitleaks). Move OWASP ZAP `dast.yml` from nightly to weekly (`0 2 * * 0`) and drop the Kong plugin check from `infrastructure.yml`.
   - **Admission control:** Retain keyless `cosign` image signing + SBOM + provenance in `container-image.yml` and digest-pinned deployments in `deploy.yml`; make in-cluster Kyverno enforcement (`infrastructure/kubernetes/policies`) opt-in post-launch so webhook unavailability cannot block pod scheduling in single-cluster lean operations.

### Preserved security and PRD invariants

None of the simplifications above relax the platform's core security architecture or PRD requirements:

- **Modular monolith ([ADR-0001](0001-modular-monolith.md)):** Spring Modulith module boundaries (`ModularityTests`), schema-per-module isolation, and `event_publication` outbox remain unchanged.
- **PostgreSQL Row-Level Security ([ADR-0004](0004-row-level-security.md)):** Every transaction runs under the non-owner `diligence_app` role via `RowLevelSecurityTransactionManager`, enforcing project isolation and information barriers (target never reads `fnd.*`, client reads only `RELEASED` findings and `ISSUED` reports).
- **Immutable audit log:** `audit.audit_log` permits only `INSERT`/`SELECT` from the runtime role; triggers reject `UPDATE`, `DELETE`, and `TRUNCATE`.
- **BFF authentication ([ADR-0005](0005-bff-authentication.md)):** Tokens stay server-side in Redis; the browser receives only a sealed `HttpOnly`, `SameSite=Lax` session cookie; `diligence-web` remains a confidential PKCE client.
- **Quarantine and content validation:** Every upload passes ClamAV malware scanning, Tika magic-byte allowlist validation, and Gotenberg/PDFBox rendering before becoming `CLEAN`; nothing `PENDING`, `INFECTED`, `REJECTED`, or `FAILED` is ever served.
- **Dynamic watermarking:** Viewer page images and client PDF downloads remain stamped per request with user name, e-mail, IP address, and timestamp.
- **Tenant model & residency ([ADR-0008](0008-tenant-model.md)):** Single-firm deployment with the project as the security boundary, all production data in AWS `ap-southeast-3` (Indonesia).

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Keep full enterprise stack at launch (Azure/AWS split, Vault, RabbitMQ, OpenSearch, Kong, OTel, Kyverno) | Matches original target diagrams without refactoring | High baseline cost, > 6–8 GiB local VM requirement, unresolved ADR-0007 collision, heavy ops burden before product-market validation |
| Rewrite into AWS-proprietary serverless services (ECS/Lambda/SQS/Cognito) | Minimal Kubernetes ops | Loses Keycloak MFA/NDA flows, Spring Modulith structure, and Kubernetes portability |
| Lean Kubernetes + PostgreSQL-native queue and FTS (chosen) | Cuts 4 stateful/infra components while keeping Spring Modulith, RLS, BFF, and EKS portability intact | Requires scale-up triggers if corpus exceeds PostgreSQL FTS limits or single-AZ availability is insufficient |

## Consequences

- Local development via `compose.yaml` drops from > 6–8 GiB to ~3.5–4 GiB Docker VM RAM (no RabbitMQ, OpenSearch, OpenSearch Dashboards, Kong, Vault, or OTel Collector).
- **Scale-up triggers** (when to reverse individual simplifications):
  - **Enable `high_availability = true` and `>= 2` Keycloak replicas:** Before onboarding regulated/enterprise deals requiring Multi-AZ failover SLAs, or when concurrent active users outgrow single-replica Keycloak/RDS sizing.
  - **Re-introduce OpenSearch:** When a deployment exceeds ~100,000 indexed documents, PostgreSQL `tsvector` P95 search latency approaches the PRD 2 s threshold, or fuzzy/cross-language ranking beyond `tsvector` is needed.
  - **Re-introduce a dedicated message broker (RabbitMQ or SQS):** When ingestion throughput causes lock/polling contention on `dr.ingestion_job` or external consumers require AMQP fan-out.
  - **Enable Kyverno admission policies:** Once cluster operations support HA webhook replicas (`>= 2`) and multi-team governance requires automated in-cluster signature enforcement.
  - **Re-evaluate multi-cloud DR (ADR-0003 Option B):** If client contracts or regulators require cross-provider disaster recovery outside AWS `ap-southeast-3`.
