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
- Encryption: customer-managed KMS keys (rotation on) for documents (S3), PostgreSQL storage and Performance
  Insights, and one shared key for CloudWatch log groups.
- Logging: VPC flow logs, all EKS control-plane log types, OpenSearch audit/application/slow logs, S3 server access
  logs (separate bucket). OpenSearch runs with fine-grained access control; its master credentials go to Vault.
- RDS deletion protection is on in every environment; disable it explicitly to tear an environment down.
- Trivy findings that cannot be fixed in code are documented in `.trivyignore.yaml` or inline `#trivy:ignore`
  comments with the reason.
- `prod` changes require a reviewed plan in the pull request.
