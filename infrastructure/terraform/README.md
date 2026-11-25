# Terraform

Reference implementation targets **AWS** (EKS, RDS PostgreSQL, ElastiCache Valkey/Redis, S3, AWS Secrets Manager).
Modules have narrow interfaces so a different cloud can be swapped in per module.

```
terraform/
├── modules/            # Single-purpose, reusable building blocks (no provider config); kms-key is shared
├── stacks/diligence/    # Composition of modules for one environment + Secrets Manager publication
└── environments/       # Root modules: state backend, providers, per-environment sizing
    ├── dev/  staging/  prod/
```

## Usage

```bash
cd environments/dev
cp backend.hcl.example backend.hcl && cp terraform.tfvars.example terraform.tfvars   # both git-ignored
terraform init -backend-config=backend.hcl
terraform plan -out=tfplan
terraform apply tfplan
```

## Rules

- State lives in S3 with native lockfiles, encrypted; never commit `*.tfstate`, `*.tfvars` or `backend.hcl`.
- Generated credentials (DB URL, Redis host/user/password) are written to AWS Secrets Manager at
  `diligence/<env>/backend/infrastructure` and `diligence/<env>/frontend-redis`, encrypted with a customer-managed KMS
  key; operator-managed values (application DB user, Sentry DSN, frontend session/OIDC secrets, Keycloak DB/admin
  credentials) live in `diligence/<env>/backend/application`, `diligence/<env>/frontend`, and
  `diligence/<env>/keycloak`. The RDS master password is managed by RDS in Secrets Manager. Application secrets never
  appear in Kubernetes manifests.
- Cluster add-ons (NGINX Ingress Controller, External Secrets Operator, cert-manager, kube-prometheus-stack,
  Tika, Gotenberg and ClamAV workloads) are installed via GitOps/Helm or `kubectl apply -k`, not by this Terraform.
- The deploy workflow assumes `diligence-<env>-deploy` through GitHub OIDC (`modules/deploy-identity`, #164): only jobs
  of this repository running in the matching GitHub environment, allowed to describe the cluster and mapped to the
  Kubernetes group `diligence-deployer`. Prerequisites once per AWS account: the IAM OIDC provider
  `token.actions.githubusercontent.com` (audience `sts.amazonaws.com`). Per environment: apply
  `infrastructure/kubernetes/rbac/<env>` as a cluster administrator and set the GitHub environment variables
  `AWS_DEPLOY_ROLE_ARN` (output `deploy_role_arn`) and `AWS_REGION`.
- Workload access to AWS uses EKS Pod Identity (`modules/workload-identity`): one role per service account, least
  privilege (the backend: documents bucket and its key only; External Secrets Operator: this environment's Secrets
  Manager secrets and KMS key). No static AWS keys in Kubernetes.
- Cost & high availability: `high_availability` defaults to `false` (single NAT gateway, single-AZ RDS, one Redis
  node) and can be flipped to `true` without changing security posture. `modules/network` always provisions a free S3
  gateway endpoint; ECR, STS and CloudWatch Logs interface endpoints are opt-in via `enable_interface_endpoints`
  (default `false`, since interface endpoints charge hourly per AZ) and can be enabled for high-traffic scale.
  `modules/kubernetes-cluster` supports an optional tainted Spot node pool (`spot_instance_types`) for document
  processing workloads.
- Encryption: customer-managed KMS keys (rotation on) for documents (S3), PostgreSQL storage and Performance
  Insights, Secrets Manager secrets, EKS node volumes, and one shared key for CloudWatch log groups.
- Logging: VPC flow logs, all EKS control-plane log types, S3 server access logs (separate bucket).
- RDS deletion protection is on in every environment; disable it explicitly to tear an environment down.
- Trivy findings that cannot be fixed in code are documented in `.trivyignore.yaml` or inline `#trivy:ignore`
  comments with the reason.
- `prod` changes require a reviewed plan in the pull request.
