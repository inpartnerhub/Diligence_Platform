# Terraform

Reference implementation targets **AWS** (EKS, RDS PostgreSQL, ElastiCache Valkey/Redis, Amazon MQ for RabbitMQ,
Amazon OpenSearch, S3). Modules have narrow interfaces so a different cloud can be swapped in per module.

```
terraform/
├── modules/            # Single-purpose, reusable building blocks (no provider config); kms-key is shared
├── stacks/diligence/    # Composition of modules for one environment + Vault secret publication
└── environments/       # Root modules: state backend, providers, per-environment sizing
    ├── dev/  staging/  prod/
```

## Usage

```bash
cd environments/dev
cp backend.hcl.example backend.hcl && cp terraform.tfvars.example terraform.tfvars   # both git-ignored
export VAULT_ADDR=https://vault.example.com VAULT_TOKEN=...                          # or CI OIDC auth
terraform init -backend-config=backend.hcl
terraform plan -out=tfplan
terraform apply tfplan
```

## Rules

- State lives in S3 with native lockfiles, encrypted; never commit `*.tfstate`, `*.tfvars` or `backend.hcl`.
- Generated credentials (Redis, RabbitMQ) are written to Vault at `kv/diligence/<env>/backend/infrastructure`;
  operator-managed values (application DB user, Sentry DSN) live in `kv/diligence/<env>/backend/application`.
  The RDS master password is managed by RDS in Secrets Manager. Application secrets never appear in Kubernetes manifests.
- Cluster add-ons (Kong Ingress Controller, Vault Secrets Operator, cert-manager, kube-prometheus-stack,
  OpenTelemetry Collector, Tika and ClamAV workloads) are installed via GitOps/Helm, not by this Terraform.
- The deploy workflow assumes `diligence-<env>-deploy` through GitHub OIDC (`modules/deploy-identity`, #164): only jobs
  of this repository running in the matching GitHub environment, allowed to describe the cluster and mapped to the
  Kubernetes group `diligence-deployer`. Prerequisites once per AWS account: the IAM OIDC provider
  `token.actions.githubusercontent.com` (audience `sts.amazonaws.com`). Per environment: apply
  `infrastructure/kubernetes/rbac/<env>` as a cluster administrator and set the GitHub environment variables
  `AWS_DEPLOY_ROLE_ARN` (output `deploy_role_arn`) and `AWS_REGION`.
- Workload access to AWS uses EKS Pod Identity (`modules/workload-identity`): one role per service account, least
  privilege (the backend: documents bucket and its key only). No static AWS keys in Vault or Kubernetes.
- Encryption: customer-managed KMS keys (rotation on) for documents (S3), PostgreSQL storage and Performance
  Insights, and one shared key for CloudWatch log groups.
- Logging: VPC flow logs, all EKS control-plane log types, OpenSearch audit/application/slow logs, S3 server access
  logs (separate bucket). OpenSearch runs with fine-grained access control. The backend uses its own internal user, limited to the
  `diligence-*` indices (`OPENSEARCH_USERNAME/PASSWORD` in `backend/infrastructure`); the master credential is written
  only to `kv/diligence/<env>/opensearch-admin`, read by the `opensearch-bootstrap` Job that creates that user (#159).
- RDS deletion protection is on in every environment; disable it explicitly to tear an environment down.
- Trivy findings that cannot be fixed in code are documented in `.trivyignore.yaml` or inline `#trivy:ignore`
  comments with the reason.
- `prod` changes require a reviewed plan in the pull request.

## Cost / right-sizing ([ADR-0007](../../docs/adr/0007-cost-efficient-infrastructure-defaults.md))

The default is the **minimum early-scale footprint**. High availability is deferred, not security or durability — RDS
keeps 14-day automated backups, deletion protection and KMS encryption at any size, Redis and OpenSearch stay encrypted,
and documents live in versioned, encrypted S3. Every expensive dial is an input you can tune per environment:

- **`high_availability` flag** (stack input, default `false`): `false` runs single-AZ RDS, a single-instance RabbitMQ
  broker, a one-node OpenSearch domain, one Redis node and one NAT gateway. Set it `true` (and raise the instance sizes
  below) when load justifies it and the same stack becomes Multi-AZ RDS, a 3-broker RabbitMQ cluster, a 3-node
  OpenSearch domain, two Redis nodes and one NAT per AZ — no other change. Review it as a normal prod plan.
- **VPC endpoints** (`modules/network`): an S3 **gateway** endpoint (free) plus ECR/STS/Logs **interface** endpoints keep
  document, image-pull, Pod Identity and log traffic off the NAT gateways, where data processing is billed per GB — the
  biggest silent cost on a document-heavy platform. Always on; no configuration needed.
- **Compute on Spot** (`spot_instance_types`): the stateless, retriable document-processing workloads
  (ClamAV/Tika/Gotenberg/ingestion) tolerate a tainted Spot pool and *prefer* it, falling back to the on-demand pool
  when Spot is reclaimed — set in `environments/prod`, empty elsewhere. ~70 % cheaper for that compute, no availability loss.
- **Instance size**: `db_instance_class`, `rabbitmq_instance_type`, `opensearch_instance_type`,
  `opensearch_instance_count` and the node types/counts are per-environment inputs. Prod launches lean (`db.t4g.large`,
  `mq.t3.micro`, `1 × t3.medium.search`, two `m7i.large` nodes); a `null` managed-service size takes the default for the
  current `high_availability` mode. Review RDS `FreeableMemory`/CPU and the Amazon MQ/OpenSearch metrics before raising.
- **Further scale-to-zero**: `infrastructure/kubernetes/components/keda-ingestion` takes the ingestion worker to zero
  between uploads via KEDA (opt-in; see its README).
