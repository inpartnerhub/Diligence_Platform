# Architecture Simplification & Over-Engineering Reduction — Review Report

> **Date:** 2026-11-25  
> **Branch:** `main` (uncommitted working tree ready for review & commit)  
> **Decision Record:** [ADR-0011: Lean architecture simplification for launch](adr/0011-lean-architecture-simplification.md)

This report summarizes the full-stack simplification carried out across backend, database, frontend, Terraform, Kubernetes, Docker Compose, CI/CD, and documentation so a fresh review session can audit every change, run Docker/Terraform checks in a CI or Docker-enabled environment, and sign off before committing.

---

## 1. Before vs. After Summary

| Dimension | Before | After |
|---|---|---|
| **Target Cloud & HA** | Unresolved split between AWS EKS (`main`/`production` drift) and unbuilt Azure AKS (`ADR-0007` collision); `prod` sized at `3× m7i.xlarge`, `db.r7g.xlarge` Multi-AZ, 3 NATs | Single AWS Jakarta (`ap-southeast-3`) target with `high_availability = false` launch default (`2× m7i.large`, `db.t4g.large` Single-AZ, 1 NAT, free S3 Gateway + ECR/STS/Logs VPC endpoints, optional Spot pool for processing/ingestion) |
| **Async Document Queue** | RabbitMQ (3-broker Amazon MQ cluster in prod / K8s operator + quorum queues + DLQ + AMQP outbox externalization) for 1 queue | PostgreSQL `dr.ingestion_job` table polled via `SELECT ... FOR UPDATE SKIP LOCKED` (`IngestionJobQueue` + `IngestionJobWorker`) |
| **Full-Text Search** | OpenSearch (3× `r7g.large.search` in prod + bootstrap Job + `opensearch-java` client) for 1 index | PostgreSQL native FTS `dr.document_search_index` (`tsvector` generated column for `simple` + `indonesian` + `english`, GIN index, `websearch_to_tsquery`, `ts_headline`) behind unchanged `SearchIndex` port |
| **Secrets Management** | 2 self-hosted HashiCorp Vault Raft clusters + Vault Secrets Operator + 6 `VaultStaticSecret` CRDs + Terraform `vault` provider | AWS Secrets Manager (`diligence/<env>/backend/infrastructure`, `diligence/<env>/frontend-redis`, KMS CMK) synced via External Secrets Operator (`ExternalSecret` + EKS Pod Identity) |
| **Edge / API Gateway** | Kong API Gateway + Kong Ingress Controller + Redis ACL `gateway` user + `KongPlugin` CRDs + Kong Vault secrets | Standard `ingressClassName: nginx` (`proxy-body-size: 100m`, `limit-rpm: 600`, TLS) + direct internal `diligence-frontend` → `diligence-backend:8080` |
| **Rate Limiting Layers** | 3 layers (Kong IP + Next.js BFF custom Redis Lua script + Backend per-user Redis) | 2 layers (NGINX Ingress per-IP + Backend `UserRateLimitFilter` per-user in Redis) |
| **Processing Pods (K8s)** | `clamav`, `tika`, `gotenberg` at `replicas: 2` each on on-demand nodes | `replicas: 1` each with Spot tolerations & preferred node affinity (automatic fallback to on-demand) |
| **Local Compose Stack** | Required `>= 6.0 GiB` (core) / `>= 8.0 GiB` (all) RAM; ran RabbitMQ, OpenSearch, OpenSearch Dashboards, Kong, Vault, OTel Collector | Reduced to `>= 3.5 GiB` (core) / `>= 4.0 GiB` (observability) RAM; removed 6 heavy containers (`opensearch`, `opensearch-dashboards`, `rabbitmq`, `kong`, `vault`, `otel-collector`) |
| **Observability** | Prometheus + Grafana + OTel Collector + OpenSearch + OpenSearch Dashboards + Sentry | Prometheus + Grafana (optional observability profile) + structured ECS JSON logs to stdout + Sentry (`send-default-pii: false`) |
| **CI/CD & Admission** | Dual SAST (CodeQL + SonarQube), nightly OWASP ZAP DAST (`0 2 * * *`), always-on fail-closed Kyverno webhook | Single SAST (CodeQL), weekly OWASP ZAP DAST (`0 2 * * 0`), keyless `cosign` signing + digest pinning retained, Kyverno documented as opt-in post-launch |

---

## 2. Changelog by Domain

Overall working tree delta prior to adding this report: **`113` modified/deleted tracked files (`569 insertions(+)`, `2456 deletions(-)`), `10` new files created (`29` obsolete files deleted).**

### A. Backend & Database (`database/migrations/postgresql/`, `apps/backend/`)
* **Created**:
  * [V202611251000__lean_postgres_queue_and_search.sql](file:///Users/mac/inp_2/database/migrations/postgresql/V202611251000__lean_postgres_queue_and_search.sql) — Adds `dr.ingestion_job` (partial indexes, RLS policies `ingestion_job_enqueue` & `ingestion_job_worker`) and `dr.document_search_index` (generated weighted `tsvector`, GIN index, RLS policies `document_search_index_read` & `document_search_index_write`) with grants to `diligence_app`.
  * [IngestionJobQueue.java](file:///Users/mac/inp_2/apps/backend/src/main/java/com/inpartnerhub/diligence/ingestion/internal/IngestionJobQueue.java) — Enqueues `DocumentUploaded` in the caller's upload transaction via `@EventListener`; recovers stale `PROCESSING` locks, claims jobs via `FOR UPDATE SKIP LOCKED`, completes/retries inside `TransactionTemplate` (`app.role = 'system'`).
  * [IngestionJobWorker.java](file:///Users/mac/inp_2/apps/backend/src/main/java/com/inpartnerhub/diligence/ingestion/internal/IngestionJobWorker.java) — `@Scheduled` worker gated by `diligence.ingestion.listener-enabled` (still `false` on API pods, `true` on `diligence-ingestion` Deployment).
  * [PostgresSearchIndex.java](file:///Users/mac/inp_2/apps/backend/src/main/java/com/inpartnerhub/diligence/search/internal/PostgresSearchIndex.java) — Implements `SearchIndex` with `TransactionTemplate`-wrapped writes (`external_gte`-equivalent `WHERE uploaded_at <= EXCLUDED.uploaded_at` guard) and parameterized `websearch_to_tsquery` + `ts_rank_cd` + `ts_headline` queries.
  * Unit & integration tests: [IngestionJobQueueTest.java](file:///Users/mac/inp_2/apps/backend/src/test/java/com/inpartnerhub/diligence/ingestion/internal/IngestionJobQueueTest.java), [PostgresSearchIndexTest.java](file:///Users/mac/inp_2/apps/backend/src/test/java/com/inpartnerhub/diligence/search/internal/PostgresSearchIndexTest.java), [PostgresSearchIndexIntegrationTest.java](file:///Users/mac/inp_2/apps/backend/src/test/java/com/inpartnerhub/diligence/search/internal/PostgresSearchIndexIntegrationTest.java).
* **Modified**:
  * [build.gradle.kts](file:///Users/mac/inp_2/apps/backend/build.gradle.kts) & [libs.versions.toml](file:///Users/mac/inp_2/apps/backend/gradle/libs.versions.toml) — Removed `rabbit-amqp-client`, `spring-boot-starter-amqp`, `spring-modulith-events-amqp`, `testcontainers-rabbitmq`, `opensearch-java`, and `sonarqube` plugin/block. **Note:** `httpclient5` was intentionally kept because [PinnedWebhookTransport.java](file:///Users/mac/inp_2/apps/backend/src/main/java/com/inpartnerhub/diligence/admin/internal/PinnedWebhookTransport.java) uses `org.apache.hc.client5.*` / `core5.*`.
  * [application.yml](file:///Users/mac/inp_2/apps/backend/src/main/resources/application.yml) — Removed `spring.rabbitmq.*`, `spring.modulith.events.externalization`, `diligence.search.*`; added `diligence.ingestion.poll-interval` (`2s`) and `stale-lock-timeout` (`10m`).
  * [DocumentUploaded.java](file:///Users/mac/inp_2/apps/backend/src/main/java/com/inpartnerhub/diligence/dataroom/DocumentUploaded.java) — Removed `@Externalized`.
  * [DataRoomRetentionListener.java](file:///Users/mac/inp_2/apps/backend/src/main/java/com/inpartnerhub/diligence/dataroom/internal/DataRoomRetentionListener.java) & [DataRoomRetentionListenerTest.java](file:///Users/mac/inp_2/apps/backend/src/test/java/com/inpartnerhub/diligence/dataroom/internal/DataRoomRetentionListenerTest.java) — Added `DELETE FROM dr.ingestion_job WHERE project_id = ?` on project purge inside `dataroom` to keep Spring Modulith boundaries clean.
  * [IngestionPipeline.java](file:///Users/mac/inp_2/apps/backend/src/main/java/com/inpartnerhub/diligence/ingestion/internal/IngestionPipeline.java) & [IngestionProperties.java](file:///Users/mac/inp_2/apps/backend/src/main/java/com/inpartnerhub/diligence/ingestion/internal/IngestionProperties.java) — Removed `@RabbitListener`, added poll/lock timeout config while keeping test constructor overloads.
  * [TestContainersConfiguration.java](file:///Users/mac/inp_2/apps/backend/src/test/java/com/inpartnerhub/diligence/TestContainersConfiguration.java), [DataRoomIntegrationTest.java](file:///Users/mac/inp_2/apps/backend/src/test/java/com/inpartnerhub/diligence/dataroom/internal/DataRoomIntegrationTest.java), [RedisAccessStringsIntegrationTest.java](file:///Users/mac/inp_2/apps/backend/src/test/java/com/inpartnerhub/diligence/RedisAccessStringsIntegrationTest.java), plus Javadoc updates in `ApplicationIntegrationTest`, `ProjectPurgeIntegrationTest`, `SearchReindexIntegrationTest`, `RequestIntegrationTest`, `DocumentSearchService`, `SearchIndexRequested`, `SearchIndex`, `ingestion/package-info.java`, `search/package-info.java`.
* **Deleted (7 files)**: `IngestionMessagingConfiguration.java`, `shared/messaging/MessagingConfiguration.java`, `shared/messaging/MessagingTopology.java`, `OpenSearchConfiguration.java`, `OpenSearchIndex.java`, `SearchProperties.java`, `OpenSearchIndexIntegrationTest.java`.

### B. Frontend (`apps/frontend/`)
* **Modified**:
  * [proxy.ts](file:///Users/mac/inp_2/apps/frontend/src/proxy.ts) & [proxy.test.ts](file:///Users/mac/inp_2/apps/frontend/src/proxy.test.ts) — Removed BFF rate-limiting logic; preserved `isCrossSiteWrite` CSRF guard, `/auth/` handling, `diligence_sid` optimistic check, and CSP nonce injection.
  * [apps/frontend/.env.example](file:///Users/mac/inp_2/apps/frontend/.env.example), [backendUrl.test.ts](file:///Users/mac/inp_2/apps/frontend/src/lib/api/backendUrl.test.ts), [client.ts](file:///Users/mac/inp_2/apps/frontend/src/lib/api/client.ts) — Removed `RATE_LIMIT_*`, pointed `API_BASE_URL` directly to backend (`http://localhost:8080`).
* **Deleted (3 files)**: `src/lib/security/rateLimiter.ts`, `src/lib/security/rateLimiter.test.ts`, `sonar-project.properties`.

### C. Infrastructure & Local Dev (`infrastructure/`, `compose.yaml`, `.env.example`, `Makefile`)
* **Terraform**:
  * Deleted `modules/rabbitmq/` (4 files) and `modules/opensearch/` (4 files).
  * Updated [modules/redis/access-strings.json](file:///Users/mac/inp_2/infrastructure/terraform/modules/redis/access-strings.json), `main.tf`, `outputs.tf` (removed `gateway` user).
  * Ported VPC endpoints (`s3` Gateway + `ecr.api`/`ecr.dkr`/`sts`/`logs` Interface) to [modules/network/main.tf](file:///Users/mac/inp_2/infrastructure/terraform/modules/network/main.tf) and optional tainted Spot pool to [modules/kubernetes-cluster/main.tf](file:///Users/mac/inp_2/infrastructure/terraform/modules/kubernetes-cluster/main.tf) (retaining `enableNetworkPolicy = "true"`).
  * Updated [stacks/diligence/main.tf](file:///Users/mac/inp_2/infrastructure/terraform/stacks/diligence/main.tf), `variables.tf`, `outputs.tf`, `versions.tf` — Added `high_availability` (default `false`), `spot_instance_types`, AWS Secrets Manager secrets + KMS key (`module.secrets_key`) + External Secrets Operator Pod Identity (`module.external_secrets_identity`), and removed Vault resources/provider.
  * Updated `environments/{dev,staging,prod}/{providers.tf,.terraform.lock.hcl}` (removed `vault` provider) and [environments/prod/main.tf](file:///Users/mac/inp_2/infrastructure/terraform/environments/prod/main.tf) (lean launch sizing).
* **Kubernetes**:
  * Created [base/backend/external-secret.yaml](file:///Users/mac/inp_2/infrastructure/kubernetes/base/backend/external-secret.yaml) and [base/frontend/external-secret.yaml](file:///Users/mac/inp_2/infrastructure/kubernetes/base/frontend/external-secret.yaml) (`external-secrets.io/v1beta1`, `ClusterSecretStore: aws-secrets-manager`).
  * Deleted `base/search/` (5 files), `base/gateway/kong-plugins.yaml`, `base/gateway/vault-secret.yaml`, `base/backend/vault-secret.yaml`, `base/frontend/vault-secret.yaml`.
  * Updated [base/gateway/ingress.yaml](file:///Users/mac/inp_2/infrastructure/kubernetes/base/gateway/ingress.yaml) (`ingressClassName: nginx`), [base/common/network-policies.yaml](file:///Users/mac/inp_2/infrastructure/kubernetes/base/common/network-policies.yaml) (`ingress-nginx` + direct `frontend` → `backend:8080`), `overlays/{dev,staging,prod}/kustomization.yaml`, `processing/{clamav,gotenberg,tika}.yaml` (`replicas: 1` + Spot tolerations/affinity), `base/ingestion/deployment.yaml`, `base/backend/prometheusrule.yaml`, `rbac/base/deployer.yaml`, `policies/verify-images.yaml`, `scripts/verify-network-policies.sh`.
* **Compose & Local Monitoring**:
  * Deleted `infrastructure/docker/kong/kong.yml` and `infrastructure/docker/otel-collector/config.yaml`.
  * Updated [compose.yaml](file:///Users/mac/inp_2/compose.yaml), [.env.example](file:///Users/mac/inp_2/.env.example), [Makefile](file:///Users/mac/inp_2/Makefile), `infrastructure/docker/prometheus/{alerts.yml,prometheus.yml}`, `infrastructure/docker/grafana/{provisioning/datasources/datasources.yml,dashboards/README.md}`.

### D. CI/CD, ADRs & Docs
* **Workflows**: Updated [backend.yml](file:///Users/mac/inp_2/.github/workflows/backend.yml), [frontend.yml](file:///Users/mac/inp_2/.github/workflows/frontend.yml), [dast.yml](file:///Users/mac/inp_2/.github/workflows/dast.yml) (weekly Sunday `0 2 * * 0`), [infrastructure.yml](file:///Users/mac/inp_2/.github/workflows/infrastructure.yml) (dropped Kong plugin check).
* **ADRs**: Created [ADR-0011](file:///Users/mac/inp_2/docs/adr/0011-lean-architecture-simplification.md); updated header statuses on ADRs `0002`, `0003`, `0006`, `0007`, `0009`, `0010` while keeping historical rationale intact.
* **Docs**: Updated [ARCHITECTURE.md](file:///Users/mac/inp_2/ARCHITECTURE.md), [README.md](file:///Users/mac/inp_2/README.md), [docs/runbooks/README.md](file:///Users/mac/inp_2/docs/runbooks/README.md), [tests/performance/README.md](file:///Users/mac/inp_2/tests/performance/README.md), [infrastructure/terraform/README.md](file:///Users/mac/inp_2/infrastructure/terraform/README.md).

---

## 3. Verification Results Observed Locally

### Passed Checks
| Check | Command | Observed Result |
|---|---|---|
| **Backend build, unit tests, Spring Modulith & Jacoco >= 70%** | `cd apps/backend && JAVA_HOME=/opt/homebrew/opt/java ./gradlew build --no-daemon` | `BUILD SUCCESSFUL` (`:compileJava`, `:compileTestJava`, `:test` incl. `ModularityTests`, `IngestionJobQueueTest`, `PostgresSearchIndexTest`, `:jacocoTestCoverageVerification`, `:bootJar`) |
| **Frontend format, lint, typecheck, unit tests** | `cd apps/frontend && npm run format:check && npm run lint && npm run typecheck && npm test` | Exit 0: Prettier OK, ESLint 0 errors, `tsc --noEmit` 0 errors, Vitest `26` files / `106` tests passed |
| **Frontend production build** | `cd apps/frontend && npm run build` (offline/sandboxed) | Exit 0: `prebuild` (`extract-antd-css.mjs`), compiled in 18.4s, TypeScript 3.1s, `20/20` static pages + `Proxy (Middleware)` |
| **Kubernetes Kustomize render (all 8 targets)** | `kubectl kustomize infrastructure/kubernetes/<dir>` for `overlays/{dev,staging,prod}`, `processing`, `rbac/{dev,staging,prod}`, `policies` | Exit 0 on all 8 directories |
| **Image pin & no moving tags CI rule** | Rendered images check across `overlays/{dev,staging,prod}` & `processing` | Exit 0: `diligence-{clamav,gotenberg,tika}` pinned by `@sha256:`, no `:main` or `:latest` tags |
| **Prometheus alert rules sync** | `diff -u` on `groups:` in `alerts.yml` vs `prometheusrule.yaml` | 0 differences (`ALERTS SYNC OK`) |
| **Docker Compose config** | `docker compose --env-file .env.example --profile app --profile observability config -q` | Exit 0 (`COMPOSE CONFIG OK`) |
| **YAML & Markdown links** | Ruby YAML parse on compose/monitoring/workflows + relative link check across 11 docs | All valid (`YAML OK`, `Markdown links OK`) |

### Not Verified Locally (Tooling / Daemon Unavailable)
1. **`./gradlew integrationTest` (Testcontainers)**: Docker daemon was stopped locally (`DOCKER=STOPPED`), so `@Tag("integration")` tests (`PostgresSearchIndexIntegrationTest`, `DataRoomIntegrationTest`, `ApplicationIntegrationTest`, `RedisAccessStringsIntegrationTest`, `SearchReindexIntegrationTest`, `RequestIntegrationTest`, `ProjectPurgeIntegrationTest`) **have NOT been run yet**.
2. **`terraform fmt -check`, `terraform validate`, `tflint`**: `terraform` and `tflint` CLIs are not installed locally, so Terraform HCL and `.terraform.lock.hcl` edits have not yet been validated by the Terraform CLI.
3. **`kubeconform`**: `kubeconform` binary is not installed locally (Kustomize rendered cleanly, but CRD schema validation for `external-secrets.io/v1beta1` `ExternalSecret` runs in GitHub Actions `infrastructure.yml`).
4. **Local `.env` vs `.env.example`**: Validation against the untracked local `.env` failed interpolation on `OIDC_CLIENT_SECRET` (unrelated pre-existing `.env` missing the variable added in `#175`); `.env.example` passes cleanly.

---

## 4. Security & PRD Invariants Checklist

- [x] **Modular Monolith ([ADR-0001](file:///Users/mac/inp_2/docs/adr/0001-modular-monolith.md))**: `ModularityTests` passed after all backend changes (`dataroom`, `ingestion`, `search`, `shared`).
- [x] **PostgreSQL RLS & Non-Owner Role ([ADR-0004](file:///Users/mac/inp_2/docs/adr/0004-row-level-security.md))**: Both `dr.ingestion_job` and `dr.document_search_index` enable RLS with explicit `diligence_app` grants and `prj.is_system()` / `prj.has_project_role(...)` policies; worker/index writes execute inside `TransactionTemplate` (`RowLevelSecurityTransactionManager`).
- [x] **Immutable Audit Trail**: `audit.audit_log` schema and triggers untouched.
- [x] **BFF Authentication ([ADR-0005](file:///Users/mac/inp_2/docs/adr/0005-bff-authentication.md)) & CSRF**: `isCrossSiteWrite`, `diligence_sid` cookie check, CSP nonce injection, and `diligence-web` confidential PKCE flow untouched.
- [x] **Document Quarantine & Watermarking**: ClamAV (`AlertExceedsMax yes`, `AlertEncryptedArchive yes`), Tika + Indonesian OCR, Gotenberg/PDFBox rendering, and per-request watermarking untouched; `diligence-ingestion` Deployment remains isolated with no Service and `INGESTION_LISTENER_ENABLED=true` while API pods set `false`.
- [x] **NetworkPolicies**: `default-deny-all`, `backend-ingress` (restricted to `ingress-nginx` and `monitoring`), direct `frontend` → `backend:8080`, `processing` isolation, and `enableNetworkPolicy = "true"` preserved.

---

## 5. Action Items & Follow-Up Questions for Next Review Session

1. **Run Backend Integration Tests (Priority 1)**: Start Docker Desktop / OrbStack (`>= 4 GiB` RAM) and run:
   ```bash
   cd apps/backend && ./gradlew integrationTest
   ```
   Verify `PostgresSearchIndexIntegrationTest`, `DataRoomIntegrationTest`, `ApplicationIntegrationTest`, and `RedisAccessStringsIntegrationTest` against real PostgreSQL 17 and Valkey 8 containers.
2. **Validate Terraform & Kustomize CRD Schemas**: Run CI (`infrastructure.yml`) or locally with `terraform` + `tflint` + `kubeconform` (`terraform init -backend=false && terraform validate && tflint --recursive`). Confirm AWS Secrets Manager IAM policy + External Secrets Operator `ClusterSecretStore` (`aws-secrets-manager`) deployment details in cluster bootstrap docs.
3. **Next.js Security Advisory (`next@16.4.6`)**: When `npm run build` executed with network access enabled, Next.js emitted `SecurityFatalError` recommending upgrading Next.js (`16.4.6` has an active npm security advisory). Decide whether to run `npx next upgrade` / bump `next` & `eslint-config-next` in a dedicated commit before merging.
4. **Reconcile / Retire `production` Branch**: Branch `production` still sits on `4ceadfe` (the old `ADR-0007` cost commit behind `main`). Once this simplification PR lands on `main`, fast-forward or archive/recreate `production` from `main`.
5. **Clean Up Historical Comments (Optional / Low Priority)**: Some internal Javadoc/comments in `apps/backend` (e.g., `application.yml` header, `RateLimitProperties.java`, `UserRateLimitFilter.java`, `FailedEventRetryJob.java`, `SearchReindexController.java`) and `.github/workflows/performance.yml` line 6 still mention "Kong", "Vault", or "OpenSearch" in prose; plus legacy runbooks `docs/runbooks/dead-letter-queue.md` and `docs/runbooks/opensearch-reindex.md` can be renamed/trimmed in a follow-up cleanup PR.
6. **Future Frontend Cleanup (Optional)**: Consider revisiting the `next.config.ts` `--webpack` + `extract-antd-css.mjs` + `dynamicCssNonce.ts` workaround if Ant Design / Next.js Turbopack CSP nonce support matures.
