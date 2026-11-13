# ADR-0010: Secrets platform on Azure — Vault topology and Kubernetes authentication per environment

- **Status:** Proposed — awaiting the Tech Lead / Architect and the Security Officer (#170)
- **Date:** 2026-11-13
- **Deciders:** Tech Lead / Architect, Security Officer, cluster owner (ADR-0007)

## Context

ADR-0002 makes HashiCorp Vault the source of truth for secrets, delivered by the Vault Secrets Operator. The
repository assumes:

- KV v2 mount `kv`, paths `diligence/<env>/…` (`backend/infrastructure` and `frontend-redis` written by Terraform;
  `backend/application`, `frontend` and `opensearch-admin` by operators or Terraform);
- `VaultAuth` resources using the Kubernetes auth method on a mount named `kubernetes`, with roles
  `diligence-backend`, `diligence-frontend` and `diligence-opensearch-bootstrap`, the same in every overlay;
- a Terraform `vault` provider that authenticates from the environment.

Vault itself, its auth mounts, roles and policies are not in the repository, so least privilege cannot be verified
(AUD-10, #170). Each environment has its own cluster, and a Kubernetes auth mount trusts exactly one cluster, so one
mount named `kubernetes` cannot serve three clusters in one Vault.

ADR-0003 moved the platform to Azure Indonesia Central (AKS, ADR-0007), with backup copies on AWS Jakarta and an
on-prem option for specific clients. That makes Azure Key Vault a candidate as well.

## Options

| Option | Meaning | Pros | Cons |
|--------|---------|------|------|
| A. One Vault, one auth mount per cluster | Mounts `kubernetes-dev`, `kubernetes-staging`, `kubernetes-prod`; overlays set the mount in `VaultAuth` | One system to run; the `diligence/<env>/` paths already fit | Production secrets share a Vault with dev; a Vault-wide mistake touches prod |
| B. Two Vaults: non-production (dev + staging) and production | Non-prod Vault with `kubernetes-dev` and `kubernetes-staging`; prod Vault with `kubernetes-prod` | Production isolated; a common practice | Two Vaults to run and upgrade |
| C. One Vault per environment | Three Vaults, mount `kubernetes` in each (manifests unchanged) | Strongest isolation | Three Vaults to run for a small workload |
| D. Azure Key Vault instead of Vault | One Key Vault per environment, Secrets Store CSI driver or External Secrets Operator, Entra Workload ID | Managed, in Indonesia Central, no unseal or upgrades | Replaces ADR-0002 and the Vault operator integration; Terraform writes to Key Vault instead of KV paths; the on-prem option and the AWS backup site need another secrets store |

## Recommendation

**Option B — keep Vault (ADR-0002), as two Vaults: non-production and production**, running in Indonesia:

- Vault on AKS (a dedicated namespace or node pool, Raft storage, at least three replicas in production) with
  auto-unseal through **Azure Key Vault** in Indonesia Central, so the managed service holds only the unseal key.
- Keeping Vault keeps the application, the Vault Secrets Operator wiring and the on-prem option unchanged; Option D
  stays the fallback if nobody can own Vault operations (same owner as ADR-0007).

Terraform owns, per environment:

1. the Kubernetes auth mount for that cluster (`kubernetes-<env>`), configured with the AKS API endpoint and CA;
2. roles bound to service account **and** namespace (`diligence-<env>`): `diligence-backend` (backend and
   ingestion), `diligence-frontend`, `diligence-opensearch-bootstrap`;
3. read-only policies per path:

| Role | May read | Must not read |
|------|----------|---------------|
| `diligence-backend` | `kv/data/diligence/<env>/backend/*` | `frontend`, `frontend-redis`, `opensearch-admin` |
| `diligence-frontend` | `kv/data/diligence/<env>/frontend`, `kv/data/diligence/<env>/frontend-redis` | `backend/*`, `opensearch-admin` |
| `diligence-opensearch-bootstrap` | `kv/data/diligence/<env>/opensearch-admin` | everything else |

4. a post-apply check that the frontend role cannot read `backend/*` and the backend role cannot read `frontend`
   (the acceptance criterion of #170).

The overlays set `spec.mount` of the three `VaultAuth` resources to `kubernetes-<env>`. Vault's own snapshots are
backed up like the databases, including the copies to the AWS site (ADR-0003).

## Decision

Pending.

## Consequences (if the recommendation is accepted)

- #170 can be implemented: Vault deployment and Key Vault auto-unseal, `vault_auth_backend`,
  `vault_kubernetes_auth_backend_config`, `vault_kubernetes_auth_backend_role` and `vault_policy` per environment,
  the overlay patches and the read check.
- The Terraform `vault` provider of each environment points at the Vault that serves it (non-prod or prod).
- Recreating a cluster or rotating its CA means re-applying the auth mount configuration.
