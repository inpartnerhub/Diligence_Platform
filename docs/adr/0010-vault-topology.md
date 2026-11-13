# ADR-0010: Vault topology and Kubernetes authentication per environment

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
(AUD-10, #170). Each environment has its own EKS cluster, and a Kubernetes auth mount trusts exactly one cluster, so
one mount named `kubernetes` cannot serve three clusters in one Vault.

## Options

| Option | Meaning | Pros | Cons |
|--------|---------|------|------|
| A. One Vault, one auth mount per cluster | Mounts `kubernetes-dev`, `kubernetes-staging`, `kubernetes-prod`; overlays set the mount in `VaultAuth` | One system to run; the `diligence/<env>/` paths already fit | Production secrets share a Vault with dev; a Vault-wide mistake (policy, root token, outage) touches prod |
| B. Two Vaults: non-production (dev + staging) and production | Non-prod Vault with `kubernetes-dev` and `kubernetes-staging`; prod Vault with `kubernetes-prod` (the `kubernetes` name could stay there) | Production isolated; dev mistakes cannot reach prod secrets; a common practice | Two systems to run and upgrade |
| C. One Vault per environment | Three Vaults, mount `kubernetes` in each (manifests unchanged) | Strongest isolation; no overlay change | Three systems to run for a small workload |

## Recommendation

**Option B**, with Terraform owning, per environment:

1. the Kubernetes auth mount for that cluster (`kubernetes-<env>`), configured with the cluster's API endpoint and CA
   from `modules/kubernetes-cluster`;
2. roles bound to service account **and** namespace (`diligence-<env>`):
   `diligence-backend` (backend and ingestion), `diligence-frontend`, `diligence-opensearch-bootstrap`;
3. read-only policies per path:

| Role | May read | Must not read |
|------|----------|---------------|
| `diligence-backend` | `kv/data/diligence/<env>/backend/*` | `frontend`, `frontend-redis`, `opensearch-admin` |
| `diligence-frontend` | `kv/data/diligence/<env>/frontend`, `kv/data/diligence/<env>/frontend-redis` | `backend/*`, `opensearch-admin` |
| `diligence-opensearch-bootstrap` | `kv/data/diligence/<env>/opensearch-admin` | everything else |

4. a test in CI or a post-apply check that the frontend role cannot read `backend/*` and the backend role cannot read
   `frontend` (the acceptance criterion of #170).

The overlays set `spec.mount` of the three `VaultAuth` resources to `kubernetes-<env>`.

Where Vault runs (self-hosted on a dedicated cluster or a managed service) must also follow ADR-0003: keys and
secrets for client data should not be held outside Indonesia without Legal's agreement.

## Decision

Pending.

## Consequences (if the recommendation is accepted)

- #170 can be implemented: `vault_auth_backend`, `vault_kubernetes_auth_backend_config`,
  `vault_kubernetes_auth_backend_role` and `vault_policy` in the stack, the overlay patches and the read check.
- The Terraform `vault` provider of each environment points at the Vault that serves it (non-prod or prod).
- Rotating the cluster CA or recreating a cluster means re-applying the auth mount configuration.
