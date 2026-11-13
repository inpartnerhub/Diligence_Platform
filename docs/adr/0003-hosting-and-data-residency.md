# ADR-0003: Hosting model and data residency

- **Status:** Accepted (technical decision, 2026-11-13) — the Legal questions below remain open (PRD 15.2, #69)
- **Date:** 2026-10-30, decided 2026-11-13
- **Deciders:** Steering committee (Partner, Product Owner, Tech Lead), Security Officer, Legal

## Context

PRD Bab 7 and 15 require alignment with UU No. 27/2022 (PDP) and ask whether clients require data to stay in
Indonesia, and whether the platform runs on public cloud, private cloud or on-premise. The answer drives the
Terraform target, the object storage product and the backup/DR design.

The platform holds confidential deal documents and personal data of client and target staff (names, e-mail
addresses, IP addresses in the audit log, documents that may contain anything). Several open issues wait for this
decision: immutable backups outside the primary region (#178), the subprocessor and cross-border transfer assessment
(#187), the EKS vs ECS choice (#183) and the production Keycloak hosting (#169).

## Options

| Option | Pros | Cons |
|--------|------|------|
| A. AWS ap-southeast-3 (Jakarta) | Data in Indonesia; managed PostgreSQL, Valkey, MQ, OpenSearch, S3; the current Terraform fits and staging/prod already refuse other regions (#163) | AWS's only region in Indonesia: no in-country second region for DR or backup copies; some services/instance types arrive later in this region; cost |
| B. Other public cloud region in Indonesia (Azure, GCP) | Data in Indonesia; Entra ID affinity (Azure) | Terraform modules rewritten per service; same single-region limits to check per provider |
| C. Private cloud / on-premise Kubernetes | Full control; satisfies strict client clauses | The team operates PostgreSQL, Redis, RabbitMQ, OpenSearch, S3-compatible storage (SeaweedFS, Ceph), backups and DR itself |
| D. Hybrid: A by default + dedicated on-prem deployments for specific clients | Commercial flexibility for clients with strict clauses | Two operating models; release discipline |

## Current state

The repository ships the AWS reference implementation (`infrastructure/terraform`). Following the interim rule below,
the environment examples and the backend's `S3_REGION` use `ap-southeast-3`, and the staging and prod root modules
refuse any other region (#163). Everything above the infrastructure layer is cloud-neutral: containers, Kubernetes,
S3-compatible storage (SeaweedFS locally; Ceph or similar on-prem), OpenSearch, RabbitMQ, Keycloak, Vault.

Data that already leaves the primary environment, or may, by design:

| Flow | What | Where it goes |
|------|------|---------------|
| Error tracking (Sentry, optional) | Stack traces, request paths; `send-default-pii: false` | The Sentry organization's region |
| E-mail (SMTP relay, e.g. Amazon SES) | Recipient address, invitation and notification text | The relay's region |
| SSO (Microsoft Entra ID / Google, optional) | Sign-in of internal users | The identity provider |
| Webhooks (ADM-07) | Event payloads configured by the Super Admin | Receivers' own hosting |

## Recommendation considered

The draft recommended Option A (AWS ap-southeast-3, the existing Terraform) with backups in a separate AWS account in
the same region. The decision below chose Option B instead.

## Open questions for Legal

- Do any target client segments (banks and other OJK-supervised institutions, state-owned enterprises, public
  sector) require their data, or their electronic systems, to be in Indonesia, or need regulator approval otherwise?
- For the flows above that leave Indonesia: is the UU PDP transfer basis (adequate protection in the receiving
  country, appropriate safeguards such as contractual clauses, or consent) in place for each, and what must the
  privacy notice say?
- Do client contracts or regulators accept two cloud providers (Azure primary, AWS for backup copies) as processors,
  and must both be named to clients?

## Decision

1. **Primary platform: Microsoft Azure, region Indonesia Central (Option B)**, for every environment that holds real
   client data (dev may use it too). Dedicated on-premise deployments for specific clients stay possible (Option D):
   everything above the infrastructure layer remains cloud-neutral.
2. **Second site for backups and disaster recovery: AWS ap-southeast-3 (Jakarta)**, in an account used only for this.
   Backups of the database and of the document storage are copied there and kept immutable. Data stays in Indonesia;
   a loss of the Azure region is recovered from the AWS copies. Restore procedure and targets (RPO ≤ 1 h,
   RTO ≤ 4 h) are proven by the restore test (#68) against this setup.
3. **No production data outside Indonesia.** The flows in the table above that leave the country (Sentry, the e-mail
   relay, SSO brokers, webhooks) are minimised and recorded in the subprocessor list (#187); each needs the transfer
   basis Legal confirms.
4. The Legal questions above stay open and are tracked in #187; they may add constraints but do not change the
   platform choice unless Legal finds a blocker.

## Consequences

The AWS reference implementation in `infrastructure/terraform` no longer describes the target platform. Moving to
Azure is a project of its own (new issues, planned before go-live):

| Area | AWS implementation today | Azure target (availability in Indonesia Central to confirm per service) |
|------|--------------------------|--------------------------------------------------------------------------|
| Kubernetes | EKS (`modules/kubernetes-cluster`), ADR-0007 | AKS; ADR-0007 must be revised (EKS → AKS) |
| Network policies (#157) | VPC CNI network policy | Azure CNI with a network policy engine (Cilium or Azure NPM) |
| Workload identity (#158) | EKS Pod Identity | Microsoft Entra Workload ID |
| Deploy identity (#164) | GitHub OIDC → IAM role | GitHub OIDC → Entra federated credential + AKS RBAC |
| Node disk encryption (#176) | CMK on EBS | Disk encryption set with a customer-managed key (Key Vault) |
| PostgreSQL | RDS | Azure Database for PostgreSQL Flexible Server |
| Redis (#171) | ElastiCache Valkey with RBAC users | Azure Cache for Redis / Azure Managed Redis with per-workload access policies (key patterns), or Valkey in the cluster |
| RabbitMQ | Amazon MQ | No managed RabbitMQ on Azure: RabbitMQ in the cluster (operator) or a change of broker |
| OpenSearch | Amazon OpenSearch Service | No managed OpenSearch on Azure: OpenSearch in the cluster or a third-party service |
| Object storage | S3 (AWS SDK in `S3DocumentStorage`, Object Lock for #179) | Blob Storage is not S3-compatible: an S3-compatible gateway or a storage adapter in the backend (decision needed); immutability via Blob immutability policies |
| E-mail | SMTP relay (SES assumed) | Azure Communication Services e-mail or another relay (record in #187) |
| Region guard (#163) | staging/prod refuse regions other than ap-southeast-3 | the same guard for Indonesia Central |
| Images and admission (#166, #167) | GHCR + cosign + Kyverno | unchanged (cloud-neutral) |
| Secrets (ADR-0002, #170) | Vault + Vault Secrets Operator | unchanged; Vault topology per ADR-0010 |
| Backups (#178) | — | Azure backups in Indonesia Central plus immutable copies to the AWS Jakarta account |

Other consequences:

- #183 (container platform), #169 (Keycloak) and #170 (Vault) are decided against Azure.
- #184 sizes Azure resources; #61 runs on the Azure staging environment.
- Until the Azure implementation exists, the AWS Terraform stays as the reference and is not applied for real client
  data.
