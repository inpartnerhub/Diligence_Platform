# ADR-0003: Hosting model and data residency

- **Status:** Proposed — decision open (PRD 15.2)
- **Date:** 2026-10-30
- **Deciders:** Steering committee (Partner, Product Owner, Tech Lead), Security Officer, Legal

## Context

PRD Bab 7 and 15 require alignment with UU No. 27/2022 (PDP) and ask whether clients require data to stay in
Indonesia, and whether the platform runs on public cloud, private cloud or on-premise. The answer drives the
Terraform target, the object storage product and the backup/DR design. Legal review of hosting location is pending.

## Options

| Option | Pros | Cons |
|--------|------|------|
| AWS ap-southeast-3 (Jakarta) | Data in Indonesia; managed PostgreSQL, Valkey, MQ, OpenSearch, S3; current Terraform fits | Some services/instance types arrive later in this region; cost |
| Other public cloud region in Indonesia (Azure, GCP) | Data in Indonesia; Entra ID affinity (Azure) | Terraform modules must be rewritten per service |
| Private cloud / on-premise Kubernetes | Full control; satisfies strict client clauses | Team operates PostgreSQL, Redis, RabbitMQ, OpenSearch, MinIO, backups, DR |
| Hybrid: SaaS by default + dedicated on-prem deployments for specific clients | Commercial flexibility | Two operating models; release discipline |

## Current state

The repository ships the AWS reference implementation (`infrastructure/terraform`, region supplied per environment
in `terraform.tfvars`). Everything above the infrastructure layer is cloud-neutral: containers, Kubernetes,
S3-compatible storage (MinIO works unchanged), OpenSearch, RabbitMQ, Keycloak, Vault.

## Decision

Pending. Until decided: use `ap-southeast-3` for any shared environment that holds real client data, and keep new
infrastructure code behind the existing module interfaces so the target can change.

## Consequences

Once decided, update `infrastructure/terraform` (modules per provider), the backup/DR runbook (RPO ≤ 1 h,
RTO ≤ 4 h) and SECURITY.md. If on-prem is chosen, add operators/Helm charts for the data services.
