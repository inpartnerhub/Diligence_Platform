# Terraform

Reference implementation targets **AWS** (EKS, RDS PostgreSQL, ElastiCache Valkey/Redis, Amazon MQ for RabbitMQ,
Amazon OpenSearch, S3). Modules have narrow interfaces so a different cloud can be swapped in per module.

```
terraform/
├── modules/            # Single-purpose, reusable building blocks (no provider config)
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
- `prod` changes require a reviewed plan in the pull request (see `.github/workflows/terraform.yml`).
