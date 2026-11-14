# Plan: moving the Diligence Platform to Azure Indonesia Central

- **Status:** Draft for review (2026-11-14)
- **Owner:** irsalshydiq (platform owner, ADR-0007)
- **Decisions this plan implements:** ADR-0003 (Azure Indonesia Central, AWS Jakarta as backup site), ADR-0007 (AKS,
  add-ons as code), ADR-0008 (one firm per deployment), ADR-0009 (Keycloak on AKS, keycloak-config-cli), ADR-0010 (two
  Vaults with Key Vault auto-unseal)

## 1. Scope and assumptions

- **Greenfield.** No environment on AWS holds real client data (ADR-0003 interim rule); there is no data to migrate. If
  that turns out to be wrong, add a data-migration workstream before cut-over.
- **Unchanged:** the application (except the storage adapter, §3), container images, GHCR + cosign signing, Kyverno
  admission (ADR-0006), the Kustomize base and NetworkPolicies, Vault + Vault Secrets Operator (ADR-0002), Kong,
  the processing namespace (Tika, Gotenberg, ClamAV), monitoring rules.
- **AWS Terraform** (`infrastructure/terraform`, 10 modules) stays as reference until Azure production is live, then is
  removed in one PR; only the backup-site account on AWS remains (§4, WS-16).
- **Local development** stays on Docker Compose (SeaweedFS for S3, Redis, RabbitMQ, OpenSearch, Keycloak, Vault).
- Every workstream follows the usual flow (branch, PR, CI, squash-merge); infrastructure is validated in CI
  (`terraform fmt/validate/tflint`, `kubectl kustomize` + `kubeconform`) and applied to **dev** first.

## 2. Target architecture

| Component | AWS reference (today) | Azure target | Decided by |
|-----------|-----------------------|--------------|------------|
| Region | ap-southeast-3 | Indonesia Central (`indonesiacentral`) | ADR-0003 |
| Kubernetes | EKS | AKS, private API server | ADR-0007 |
| Network policy enforcement (#157) | VPC CNI | Azure CNI powered by Cilium | ADR-0007 |
| Workload identity (#158) | EKS Pod Identity | Microsoft Entra Workload ID | ADR-0007 |
| Deploy identity (#164) | GitHub OIDC → IAM role, EKS access entry | GitHub OIDC → Entra federated credential, AKS RBAC | ADR-0007 |
| Node disk encryption (#176) | EBS + CMK | Disk encryption set + CMK in Key Vault | ADR-0007 |
| Keys | KMS (`modules/kms-key`) | Azure Key Vault (keys, Vault auto-unseal) | ADR-0010 |
| PostgreSQL | RDS | Azure Database for PostgreSQL Flexible Server, private access | ADR-0003 |
| Redis (#171) | ElastiCache Valkey + RBAC users | Azure Managed Redis, per-workload access policies (to verify, fallback Valkey in cluster) | this plan |
| RabbitMQ | Amazon MQ | RabbitMQ Cluster Operator on AKS | this plan, ADR-0007 |
| OpenSearch | Amazon OpenSearch Service | OpenSearch Operator on AKS | this plan, ADR-0007 |
| Document storage | S3 (`S3DocumentStorage`) | Blob Storage via a new `BlobDocumentStorage` | this plan |
| Secrets | Vault (not deployed by the repo) | Two Vaults on AKS, Key Vault auto-unseal | ADR-0010 |
| Identity provider | Keycloak (local only) | Keycloak 26.x on AKS, realm via keycloak-config-cli | ADR-0009 |
| E-mail | SMTP relay (SES assumed) | Generic SMTP relay, provider decided with Legal (#187) | open |
| Terraform state | S3 backend | Azure Storage backend (state container, locking via blob lease) | this plan |
| Backups | — | Azure-native backups in Indonesia Central + immutable copies in an AWS Jakarta account | ADR-0003 |

## 3. Application change: Blob storage adapter

`DocumentStorage` (dataroom module) already isolates storage; `S3DocumentStorage` uses put, get, delete and "delete all
versions under a prefix" (project purge, ADM-06). A `BlobDocumentStorage` implements the same interface with the Azure
Storage Blob SDK and `DefaultAzureCredential` (Workload ID): upload with content type and length, download as a
stream, delete, and delete every version and snapshot under a prefix (blob versioning on). Selection by configuration
(`diligence.object-storage.provider: s3 | azure-blob`), S3 stays the default for local development. Tests: unit tests
plus an integration test against Azurite (Testcontainers); the existing storage contract tests run against both.

## 4. Workstreams (issue candidates)

Dependencies are noted as `→ needs WS-n`. Phases can overlap where dependencies allow.

### Phase 0 — Foundation

| WS | Title | Scope | Done when |
|----|-------|-------|-----------|
| 1 | Azure landing zone and Terraform skeleton | Subscriptions (non-prod, prod) and resource groups per environment; `infrastructure/terraform-azure/` with `azurerm`/`azuread` providers; state in Azure Storage; region guard for `indonesiacentral` (port of #163); CI fmt/validate/tflint for the new tree; **check every service in §2 is available in Indonesia Central** | `terraform plan` runs in CI for dev; availability table recorded in this plan |
| 2 | Network | VNet per environment, subnets (AKS nodes, private endpoints, PostgreSQL delegated subnet), NSGs, private DNS zones (port of `modules/network`) → needs WS-1 | Dev VNet applied; private endpoints resolvable from AKS |
| 3 | Key Vault and customer-managed keys | Key Vault per environment (purge protection, RBAC), keys for disk encryption and Vault auto-unseal (port of `modules/kms-key`) → needs WS-1 | Keys exist with rotation policy; access only for the identities that need them |

### Phase 1 — Platform

| WS | Title | Scope | Done when |
|----|-------|-------|-----------|
| 4 | AKS cluster | Private API server, Azure CNI + Cilium, Workload ID + OIDC issuer, CMK disk encryption, system and user node pools, upgrade channel, Entra-integrated RBAC (ports of #157, #158, #176) → needs WS-2, WS-3 | `verify-network-policies.sh` passes on dev; nodes encrypted with the CMK |
| 5 | Deploy pipeline on Azure | GitHub OIDC → Entra federated credential per environment, AKS RBAC binding to `diligence-deployer` (port of #164); `deploy.yml`: `azure/login` + `az aks get-credentials` instead of AWS steps → needs WS-4 | A deploy to dev from `main` succeeds by digest; no long-lived credentials |
| 6 | Platform add-ons as code | `infrastructure/kubernetes/platform/`: Kong IC, Vault Secrets Operator, cert-manager, kube-prometheus-stack, OpenTelemetry Collector, Kyverno + `infrastructure/kubernetes/policies`, RabbitMQ and OpenSearch operators; pinned chart versions and values; CI render and kubeconform (ADR-0007) → needs WS-4 | All add-ons installed on dev from Git; Kyverno refuses an unsigned image |
| 7 | Vault (ADR-0010, implements #170) | Non-prod and prod Vaults on AKS, Raft, Key Vault auto-unseal, snapshots; `kubernetes-<env>` auth mounts, roles bound to SA + namespace, read-only policies, overlay `VaultAuth` mount patches, post-apply read check → needs WS-3, WS-4, WS-6 | Frontend role cannot read `backend/*`, backend role cannot read `frontend` (check in CI or post-apply) |

### Phase 2 — Data services

| WS | Title | Scope | Done when |
|----|-------|-------|-----------|
| 8 | PostgreSQL Flexible Server | Private access in the delegated subnet, TLS `verify-full`, CMK, PITR, zone-redundant HA in prod; databases for the application and Keycloak; `diligence_app` login with LOGIN + password (#177 rollout), owner credential for Flyway; credentials to Vault (port of `modules/postgresql`) → needs WS-2, WS-3, WS-7 | Backend migrates as owner and runs as `diligence_app` on dev |
| 9 | Azure Managed Redis | Verify per-workload access policies with key patterns (`access-strings.json`, #171) on the chosen tier; if they cannot express the patterns, Valkey in the cluster with the same ACL file; TLS; credentials to Vault (`backend/infrastructure`, `frontend-redis`) → needs WS-2, WS-7 | `RedisAccessStringsIntegrationTest` rules hold against the dev instance (backend/Kong cannot read sessions) |
| 10 | RabbitMQ on AKS | RabbitMQ Cluster Operator, 3 replicas in prod, quorum queues, TLS on 5671, persistent volumes on encrypted disks, users from Vault (port of `modules/rabbitmq`) → needs WS-6, WS-7 | Ingestion pipeline processes an upload on dev |
| 11 | OpenSearch on AKS | OpenSearch Operator, security plugin, TLS, backend user via the `opensearch-bootstrap` Job (#159), snapshots to Blob (port of `modules/opensearch`) → needs WS-6, WS-7, WS-12 | Search works on dev; snapshot and restore of the index tested |
| 12 | Blob Storage and `BlobDocumentStorage` | Storage account (private endpoint, versioning, soft delete, no public access, CMK), Workload ID role assignment scoped to the container (port of `modules/object-storage`); backend adapter (§3) with Azurite tests → needs WS-2, WS-3 (infra); adapter can start at once | Upload, preview, download and project purge work on dev with Blob |

### Phase 3 — Identity and application

| WS | Title | Scope | Done when |
|----|-------|-------|-----------|
| 13 | Keycloak on AKS (implements #169) | Keycloak 26.x (≥ 2 replicas), database from WS-8, internal-only admin route, keycloak-config-cli Job with per-environment values from the overlays and secrets from Vault, CI check against `localhost` URIs in staging/prod, runbook update; realm steps of #160, #161, #175 included → needs WS-6, WS-7, WS-8 | Sign-in with OTP and step-up works on dev; realm diff reviewable in PRs |
| 14 | Application overlays and network policies for Azure | Overlays for dev/staging/prod (endpoints, storage provider, VNet CIDRs in `backend-egress-vpc-*`/`frontend-egress-vpc-redis`, drop the Pod Identity rule), service-account Workload ID annotations → needs WS-4, WS-8–WS-13 | `kubectl kustomize` + kubeconform pass; dev fully running; DAST passes against dev |
| 15 | E-mail relay | Choose the relay with Legal (#187); SMTP settings and credentials in Vault; SPF/DKIM/DMARC for the sending domain → needs Legal input | Invitation e-mail delivered from dev |

### Phase 4 — Backup, DR and operations

| WS | Title | Scope | Done when |
|----|-------|-------|-----------|
| 16 | Backups and the AWS backup site (rewrites #178) | Azure-native backups in Indonesia Central (PostgreSQL PITR, Blob versioning/soft delete, Vault and OpenSearch snapshots); scheduled copies to an AWS ap-southeast-3 account used only for this, into S3 with Object Lock (database dumps, Blob objects, Vault snapshots); least-privilege cross-cloud identity; alerting on failed copies → needs WS-8, WS-11, WS-12 | A copy job has succeeded and deletion in the AWS account is refused (acceptance of #178) |
| 17 | Restore test (#68) | Restore PostgreSQL, documents and Vault from the Azure backups and from the AWS copies into a scratch environment; measure RPO/RTO → needs WS-16 | Record with RPO ≤ 1 h and RTO ≤ 4 h, or the gap documented |
| 18 | Audit log to WORM storage (#179) | Export to a Blob container with an immutability policy; retention per #189 → needs WS-12, #189 | New audit entries appear within minutes; deletion refused |

### Phase 5 — Validation and go-live

| WS | Title | Scope | Done when |
|----|-------|-------|-----------|
| 19 | Staging on Azure, load test and sizing | Staging environment end to end; load test (#61) and right-sizing (#184) on Azure | PRD Bab 7 targets met or gaps recorded |
| 20 | Independent penetration test (#66) | On Azure staging | Report received; findings triaged |
| 21 | Production go-live and AWS clean-up | Prod environment, go-live checklist, deputy owner named (ADR-0007); remove the AWS reference Terraform except the backup-site module | Prod live; repository describes only Azure + the AWS backup site |

## 5. Order and critical path

1. WS-1 → WS-2, WS-3 → WS-4 → WS-5, WS-6 → WS-7 (foundation and platform, dev only).
2. In parallel from the start: WS-12 adapter code (no infrastructure needed).
3. WS-8 … WS-13 on dev, then WS-14 (dev complete).
4. WS-16, WS-17, WS-18 once data services exist.
5. Staging (WS-19, WS-20), then prod (WS-21).

## 6. Risks and open points

| Risk / open point | Mitigation |
|-------------------|------------|
| A service or SKU is not available in Indonesia Central | WS-1 checks availability first; alternatives decided before WS-2 |
| Azure Managed Redis cannot express the key-pattern policies of #171 | WS-9 verifies first; fallback Valkey in the cluster with the unchanged ACL file |
| Operating RabbitMQ, OpenSearch, Vault and Keycloak in-cluster is a large surface for one owner | Deputy owner before go-live (ADR-0007); operators with pinned versions; runbooks per service; Container Apps trigger in ADR-0007 |
| Cross-cloud backup copies need an identity that can write to AWS from Azure | Workload ID federation to an AWS role (OIDC) instead of static keys; write-only, Object Lock in compliance mode after #189 |
| Legal questions of ADR-0003 (two providers, transfers, OJK clients) | Tracked in #187; answers may add constraints before go-live |
| E-mail relay undecided | WS-15 waits for #187; SMTP stays generic |
