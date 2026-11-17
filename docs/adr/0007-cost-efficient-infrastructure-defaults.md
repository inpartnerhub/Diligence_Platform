# ADR-0007: Cost-efficient infrastructure defaults

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** Maintainer (repository owner)

## Context

The `prod` stack was sized for a mature, high-load platform from day one: `db.r7g.xlarge` RDS, an `mq.m7g.large`
three-broker RabbitMQ cluster, a three-node `r7g.large.search` OpenSearch domain, and a single on-demand EKS node pool
running every workload — including the document-processing services (ClamAV, Tika, Gotenberg) and the ingestion worker,
which are bursty rather than steady. In addition, all AWS traffic — notably the large volume of document bytes to and
from S3 — left the VPC through NAT gateways, which bill data processing per GB. For an early-production deployment this
is more capacity, and more cost, than the load justifies.

The platform is launching at **early scale**: a handful of projects and users, growing later. Paying up front for
Multi-AZ failover and multi-node clusters is capacity the early load does not need. The goal is therefore the **minimum
cost for early scale, with a one-switch path to full HA later** — without weakening the project's actual goals: the
security posture (customer-managed KMS keys, IMDSv2 one-hop, Pod Identity, NetworkPolicies, signed-image admission),
the features (full-text search, the document pipeline), and **data durability and confidentiality** (encrypted,
backed-up storage). High availability / redundancy is treated as a scaling concern, not a launch requirement.

## Decision

1. **VPC endpoints keep AWS traffic off the NAT gateways** (`modules/network`). A free S3 **gateway** endpoint carries
   every object read/write; **interface** endpoints carry ECR image pulls, STS (Pod Identity) and CloudWatch Logs. This
   removes the per-GB NAT data-processing charge for the platform's heaviest traffic. Always on, every environment.
2. **Burstable, retriable workloads run on Spot** (`modules/kubernetes-cluster`). An optional, tainted Spot node pool
   (`spot_instance_types`, diversified instance types) is added in `prod`. ClamAV, Tika, Gotenberg and the ingestion
   worker tolerate the taint and *prefer* the pool via `preferredDuringScheduling` node affinity, so a Spot reclaim
   never blocks ingestion — the pods fall back to the on-demand pool, and a reclaimed ingestion node only redelivers
   in-flight messages from the durable quorum queue. The general pool that runs the API and frontend stays on-demand.
3. **High availability is a single `high_availability` flag, defaulting off for minimal early-scale cost.** When off
   (the launch default, every environment), the stack runs single-AZ RDS, a single-instance RabbitMQ broker, a one-node
   OpenSearch domain, one Redis node and one NAT gateway. When on, the same stack becomes Multi-AZ RDS, a three-broker
   RabbitMQ cluster, a three-node OpenSearch domain, two Redis nodes and one NAT per AZ — no other change. Crucially,
   **durability and encryption do not depend on this flag**: RDS keeps 14-day automated backups, deletion protection and
   KMS encryption at any size; Redis and OpenSearch stay encrypted in transit and at rest; documents live in versioned,
   encrypted S3. Instance sizes are separate per-environment inputs (`db_instance_class`, `rabbitmq_instance_type`,
   `opensearch_instance_type`, node types/counts); `prod` launches lean (`db.t4g.large`, `mq.t3.micro`,
   `1 × t3.medium.search`, two `m7i.large` nodes) and is raised alongside the flag when load demands it.
4. **Scale-to-zero for the ingestion worker is available but opt-in** (`kubernetes/components/keda-ingestion`). KEDA
   can take the queue-backed worker to zero between uploads; it is not wired into any overlay, so a cluster without the
   KEDA operator deploys unchanged.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Keep Multi-AZ / clustered from day one | Failover ready immediately | Pays for redundancy the early load does not use; the opposite of the goal |
| Tie HA to `environment == "prod"` | Simple | Forces prod to pay for HA before it is needed; the `high_availability` flag decouples the two |
| Keep single-AZ but on bigger instances | More headroom | Still over-pays; burstable Graviton is enough at early load and scales up in one change |
| Put the API/frontend on Spot too | Larger saving | User-facing latency and availability are sensitive to reclaims; only truly retriable work belongs on Spot |
| Karpenter instead of a managed Spot pool | Finer bin-packing and instance selection | A new controller to operate; a managed Spot node group is a smaller, reversible first step |

## Consequences

- Early-scale `prod` cost is a fraction of the Multi-AZ design (single-AZ, single-instance services, one NAT, smaller
  nodes), while **security, features and data durability are unchanged** — only redundancy is deferred. `dev` and
  `staging`, already lean, stay lean.
- The trade-off while `high_availability = false`: an AZ failure can cause downtime (RDS restores from backup rather
  than failing over; a single OpenSearch/RabbitMQ node or the one NAT is a single point of failure). No data is lost —
  this is availability, not durability. Scaling up is `high_availability = true` plus a size bump, reviewed as a normal
  prod plan.
- Every size is a variable with the HA-mode default available via `coalesce(...)`, so the switch is a few lines in one
  environment root once metrics (RDS `FreeableMemory`/CPU, Amazon MQ, OpenSearch) show load approaching capacity.
- Applying this adds VPC endpoints and a Spot node group; review the Terraform plan before apply. Interface endpoints
  carry a small hourly charge, offset by the NAT data-processing they remove.
- The processing services and ingestion worker may be rescheduled when Spot capacity changes; this is expected and
  absorbed by the queue retries and PodDisruptionBudgets. A Spot shortage transparently falls back to on-demand.
- The PRD availability NFR (99.5%, RPO ≤ 1 h, RTO ≤ 4 h — [docs/prd](../prd/README.md)) is met in phases: early scale
  satisfies RPO ≤ 1 h through PITR and RTO through restore; the full Multi-AZ commitment is reached by setting
  `high_availability = true` before load grows. The quarterly restore test applies throughout.
- See [infrastructure/terraform/README.md](../../infrastructure/terraform/README.md) ("Cost / right-sizing") for the
  operational knobs.
