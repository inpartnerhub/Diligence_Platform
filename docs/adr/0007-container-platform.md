# ADR-0007: Container platform — Kubernetes on AKS, with an ownership trigger for Azure Container Apps

- **Status:** Proposed — awaiting the Tech Lead / Architect and the steering committee (#183)
- **Date:** 2026-11-13
- **Deciders:** Tech Lead / Architect, Security Officer, whoever will operate production

## Context

ADR-0003 moved the target platform to **Azure Indonesia Central**, with AWS Jakarta only as the backup site. The
question of #183 — a managed Kubernetes cluster or a serverless container service — therefore becomes **AKS versus
Azure Container Apps** (the Azure counterparts of the EKS versus ECS Fargate comparison in the validation of
2026-11-12, section 5.H).

What is already Kubernetes-shaped and cloud-neutral: the Kustomize base with three overlays, Kong Ingress Controller,
the Vault Secrets Operator, Pod Security labels, NetworkPolicies (every egress rule has a destination, #180), Kyverno
image-signature admission (ADR-0006), `ServiceMonitor`/`PrometheusRule` resources, HPAs and PDBs, and the processing
namespace for Tika, Gotenberg and ClamAV. What is AWS-specific and must be rebuilt either way: the cluster module
(EKS), network policy enforcement (#157), workload identity (#158), the deploy identity (#164) and node disk
encryption (#176).

On Azure, RabbitMQ and OpenSearch have no managed service (ADR-0003), so they would run as workloads too, which adds
stateful services to whatever platform is chosen.

The workload is small: two stateless services, one ingestion worker, three parser services, RabbitMQ and OpenSearch;
business-hours load for 50–100 users.

The cluster add-ons are not defined in this repository (`infrastructure/terraform/README.md`: Kong IC, the Vault
Secrets Operator, cert-manager, kube-prometheus-stack and the OpenTelemetry Collector are installed "via
GitOps/Helm"; ADR-0006 adds Kyverno). No owner or version pins for them are recorded.

## Options

| Criterion | AKS | Azure Container Apps |
|-----------|-----|----------------------|
| Existing implementation | Manifests, overlays, gateway, Vault operator, NetworkPolicies, Kyverno and monitoring resources carry over; only the cluster Terraform is new | Rewrite: Container Apps definitions, ingress instead of Kong, Key Vault references or Dapr secrets instead of the Vault operator, environment-level networking instead of NetworkPolicies, no Kyverno admission |
| Stateful services (RabbitMQ, OpenSearch) | Operators or Helm charts with persistent volumes | Poor fit (Container Apps is meant for stateless apps); they would need VMs or third-party services |
| Operations | AKS minor upgrades (about three a year), node images, add-ons | Platform-managed; revisions only |
| Isolation | NetworkPolicies (Cilium or Azure NPM), PSA, Kyverno; node pools to manage | Per-environment network isolation; less control per workload |
| Portability (on-prem option of ADR-0003) | The same manifests run on any Kubernetes | Azure-only |
| Cost | Node pools sized to the load (#184); free or paid control-plane tier | Per vCPU-second; likely lower for the stateless part, not calculated |

## Recommendation

**AKS.** The existing manifests and security controls carry over, RabbitMQ and OpenSearch need a platform that runs
stateful workloads, and Kubernetes keeps the on-prem option of ADR-0003 open.

Conditions, to be met before go-live:

1. **Named owner** for the cluster and its add-ons (person or team, plus a deputy), recorded in this ADR.
2. **Add-ons as code**: Kong IC, Vault Secrets Operator, cert-manager, kube-prometheus-stack, OpenTelemetry Collector,
   Kyverno, and the RabbitMQ and OpenSearch operators or charts, with pinned versions and values, in this repository
   or a referenced GitOps repository.
3. **Upgrade cadence**: AKS minor version upgraded before it leaves support; a runbook (`docs/runbooks/`) for cluster
   and add-on upgrades.
4. **Azure equivalents of the AWS controls**: network policy enforcement (Azure CNI with Cilium or Azure NPM),
   Microsoft Entra Workload ID, GitHub OIDC with an Entra federated credential for deploys, node disks encrypted with a
   customer-managed key, private API server or authorized IP ranges.
5. **Right-size** node pools from the load test (#61 → #184).

**Trigger to re-evaluate:** if conditions 1–3 cannot be met by go-live, Azure Container Apps for the stateless
services (with RabbitMQ and OpenSearch moved to VMs or third-party services) is the lower-operations option; that
would need its own ADR.

## Decision

Pending.

## Consequences (if the recommendation is accepted)

- New AKS cluster module and the Azure counterparts of #157, #158, #164 and #176; the EKS module stays as reference.
- RabbitMQ and OpenSearch deployments (operators or charts) join the manifests.
- Owner and deputy are named here; add-on definitions and the upgrade runbook become go-live tasks.
