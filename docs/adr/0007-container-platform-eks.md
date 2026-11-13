# ADR-0007: Container platform — stay on EKS, with an ownership trigger for ECS Fargate

- **Status:** Proposed — awaiting the Tech Lead / Architect and the steering committee (#183)
- **Date:** 2026-11-13
- **Deciders:** Tech Lead / Architect, Security Officer, whoever will operate production

## Context

The repository targets Amazon EKS: a Terraform cluster module (`modules/kubernetes-cluster`, managed node group, VPC
CNI with network policy enforcement #157, Pod Identity #158, CMK-encrypted node volumes #176), a Kustomize base with
three overlays, Kong Ingress Controller, the Vault Secrets Operator, Pod Security labels, NetworkPolicies, Kyverno
image-signature admission (ADR-0006), `ServiceMonitor`/`PrometheusRule` resources, HPAs and PDBs, and a separate
processing namespace for Tika, Gotenberg and ClamAV.

The architecture and security validation of 2026-11-12 (section 5.H) compared this with ECS Fargate. The workload is
small (two stateless services, one in-process ingestion worker, three parser services; business-hours load for
50–100 users), so both platforms fit. ECS would give per-task security groups and encrypted ephemeral storage
without extra configuration and has no cluster to upgrade; EKS has the working implementation.

The cluster add-ons are *not* defined in this repository: `infrastructure/terraform/README.md` says Kong IC, the
Vault Secrets Operator, cert-manager, kube-prometheus-stack and the OpenTelemetry Collector are installed "via
GitOps/Helm", and ADR-0006 adds Kyverno ≥ 1.13. No GitOps repository, version pins or owner for them is recorded.

## Options

| Criterion | EKS (current) | ECS Fargate |
|-----------|---------------|-------------|
| Existing implementation | Complete: manifests, overlays, gateway, Vault operator, policies, signing admission | Rewrite: task definitions, ALB + WAF instead of Kong, Secrets Manager/ECS secrets instead of the Vault operator, security groups instead of NetworkPolicies, a different signing/admission story |
| Operations | Kubernetes minor upgrade about three times a year, node AMIs, and six add-ons to keep current | Task definitions and the platform version only |
| Isolation | NetworkPolicies (enforced since #157), PSA, Kyverno; node access to manage | Per-task ENI and security group; no nodes |
| Portability (ADR-0003 option D, on-prem) | The same manifests run on any Kubernetes | AWS-only; an on-prem deployment would need Kubernetes anyway |
| Cost | Control-plane fee per cluster plus nodes; prod sizing (`node_min_size = 3` × `m7i.xlarge`) is above what the load needs (#184) | Per task; likely lower at this size, not calculated |

## Recommendation

**Stay on EKS; do not start an ECS migration.** None of the open findings require it, the implementation is complete,
and Kubernetes keeps the on-prem option of ADR-0003 open.

Conditions, to be met before go-live:

1. **Named owner** for the cluster and its add-ons (person or team, plus a deputy), recorded in this ADR.
2. **Add-ons as code**: Kong IC, Vault Secrets Operator, cert-manager, kube-prometheus-stack, OpenTelemetry
   Collector and Kyverno with pinned chart versions and values, in this repository or a referenced GitOps
   repository.
3. **Upgrade cadence**: EKS minor version upgraded before it leaves standard support; a short runbook
   (`docs/runbooks/`) for cluster and add-on upgrades.
4. **Right-size** nodes from the load test (#61 → #184).

**Trigger to re-evaluate:** if conditions 1–3 cannot be met by go-live, ECS Fargate is the lower-risk platform for
this workload size; the migration would replace Kong with ALB + WAF, the Vault operator with Secrets Manager/ECS
secrets and NetworkPolicies with security groups, and needs its own ADR.

## Decision

Pending.

## Consequences (if the recommendation is accepted)

- Owner and deputy are named here; the add-on definitions and the upgrade runbook become go-live tasks.
- #184 sizes the node group; the EKS module stays as is.
- No change to the application or the manifests.
