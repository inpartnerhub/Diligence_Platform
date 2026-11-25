# Composition of the Diligence Platform's cloud resources for one environment.
# Generated credentials are written to AWS Secrets Manager (diligence/<env>/backend/infrastructure,
# diligence/<env>/frontend-redis) and synced into Kubernetes by the External Secrets Operator — see
# infrastructure/kubernetes/base/{backend,frontend}/external-secret.yaml.

locals {
  name = "diligence-${var.environment}"
  tags = merge(var.tags, { project = "diligence", environment = var.environment, managed-by = "terraform" })
}

# Shared customer-managed key for CloudWatch log groups (VPC flow logs, EKS control plane).
module "log_key" {
  source                = "../../modules/kms-key"
  alias                 = "${local.name}-logs"
  description           = "Encryption key for ${local.name} CloudWatch log groups"
  allow_cloudwatch_logs = true
  tags                  = local.tags
}

# Customer-managed key for AWS Secrets Manager secrets managed by this stack.
module "secrets_key" {
  source      = "../../modules/kms-key"
  alias       = "${local.name}-secrets"
  description = "Encryption key for ${local.name} Secrets Manager secrets"
  tags        = local.tags
}

module "network" {
  source                     = "../../modules/network"
  name                       = local.name
  cidr                       = var.vpc_cidr
  azs                        = var.availability_zones
  single_nat_gateway         = !var.high_availability
  enable_interface_endpoints = var.enable_interface_endpoints
  log_kms_key_arn            = module.log_key.arn
  tags                       = local.tags
}

module "cluster" {
  source              = "../../modules/kubernetes-cluster"
  name                = local.name
  vpc_id              = module.network.vpc_id
  subnet_ids          = module.network.private_subnet_ids
  node_instance_types = var.node_instance_types
  node_min_size       = var.node_min_size
  node_max_size       = var.node_max_size
  spot_instance_types = var.spot_instance_types
  spot_max_size       = var.spot_max_size
  log_kms_key_arn     = module.log_key.arn
  tags                = local.tags
}

module "postgresql" {
  source                     = "../../modules/postgresql"
  name                       = local.name
  vpc_id                     = module.network.vpc_id
  subnet_ids                 = module.network.private_subnet_ids
  allowed_security_group_ids = [module.cluster.node_security_group_id]
  instance_class             = var.db_instance_class
  multi_az                   = var.high_availability
  tags                       = local.tags
}

module "redis" {
  source                     = "../../modules/redis"
  name                       = local.name
  vpc_id                     = module.network.vpc_id
  subnet_ids                 = module.network.private_subnet_ids
  allowed_security_group_ids = [module.cluster.node_security_group_id]
  num_cache_clusters         = var.high_availability ? 2 : 1
  tags                       = local.tags
}

module "object_storage" {
  source      = "../../modules/object-storage"
  bucket_name = "${local.name}-documents-${var.bucket_suffix}"
  tags        = local.tags
}

# Backend access to the documents bucket and its key (#158): Pod Identity for diligence-<env>/diligence-backend, limited
# to what DocumentStorage does — read, write and delete objects, list them, and remove every version on a project
# purge (ListBucketVersions, DeleteObjectVersion; SECURITY.md "Retention").
data "aws_iam_policy_document" "backend_documents" {
  statement {
    sid       = "DocumentObjects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:DeleteObjectVersion"]
    resources = ["${module.object_storage.bucket_arn}/*"]
  }
  statement {
    sid       = "DocumentListing"
    actions   = ["s3:ListBucket", "s3:ListBucketVersions"]
    resources = [module.object_storage.bucket_arn]
  }
  statement {
    sid       = "DocumentKey"
    actions   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey"]
    resources = [module.object_storage.kms_key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.${data.aws_region.current.region}.amazonaws.com"]
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

module "backend_identity" {
  source          = "../../modules/workload-identity"
  name            = "${local.name}-backend"
  cluster_name    = module.cluster.cluster_name
  namespace       = "diligence-${var.environment}"
  service_account = "diligence-backend"
  policy_json     = data.aws_iam_policy_document.backend_documents.json
  tags            = local.tags
}

# Deploy workflow (#164): GitHub OIDC → this role → Kubernetes group diligence-deployer (infrastructure/kubernetes/rbac).
module "deploy_identity" {
  source             = "../../modules/deploy-identity"
  name               = "${local.name}-deploy"
  github_repository  = var.github_repository
  github_environment = var.environment
  cluster_name       = module.cluster.cluster_name
  cluster_arn        = module.cluster.cluster_arn
  tags               = local.tags
}

# Terraform owns diligence/<env>/backend/infrastructure in AWS Secrets Manager.
# Operator-managed values (DB_USERNAME, DB_PASSWORD, FLYWAY_*, SENTRY_DSN, KEYCLOAK_ADMIN_CLIENT_SECRET, SMTP_*,
# MAIL_FROM, WEBHOOK_KEY_ENCRYPTION_KEY) live in diligence/<env>/backend/application.
resource "aws_secretsmanager_secret" "backend_infrastructure" {
  name       = "diligence/${var.environment}/backend/infrastructure"
  kms_key_id = module.secrets_key.arn
  tags       = local.tags
}

resource "aws_secretsmanager_secret_version" "backend_infrastructure" {
  secret_id = aws_secretsmanager_secret.backend_infrastructure.id
  secret_string = jsonencode({
    DB_URL         = "jdbc:postgresql://${module.postgresql.endpoint}:${module.postgresql.port}/${module.postgresql.database_name}?sslmode=verify-full"
    REDIS_HOST     = module.redis.primary_endpoint
    REDIS_USERNAME = module.redis.users.backend.username
    REDIS_PASSWORD = module.redis.users.backend.password
  })
}

# The web app's (BFF) Redis connection with its own user, which alone may read sessions (#171). A separate secret:
# diligence/<env>/frontend is managed by operators.
resource "aws_secretsmanager_secret" "frontend_redis" {
  name       = "diligence/${var.environment}/frontend-redis"
  kms_key_id = module.secrets_key.arn
  tags       = local.tags
}

resource "aws_secretsmanager_secret_version" "frontend_redis" {
  secret_id = aws_secretsmanager_secret.frontend_redis.id
  secret_string = jsonencode({
    REDIS_URL = "rediss://${module.redis.users.web.username}:${module.redis.users.web.password}@${module.redis.primary_endpoint}:6379/0"
  })
}

# External Secrets Operator Pod Identity: allows reading this environment's Secrets Manager secrets and decrypting
# the Terraform-managed secrets key.
data "aws_iam_policy_document" "external_secrets" {
  statement {
    sid     = "ReadEnvironmentSecrets"
    actions = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = [
      aws_secretsmanager_secret.backend_infrastructure.arn,
      aws_secretsmanager_secret.frontend_redis.arn,
      "arn:aws:secretsmanager:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:secret:diligence/${var.environment}/backend/application-*",
      "arn:aws:secretsmanager:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:secret:diligence/${var.environment}/frontend-*",
      "arn:aws:secretsmanager:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:secret:diligence/${var.environment}/keycloak-*",
    ]
  }
  statement {
    sid       = "DecryptSecretsKey"
    actions   = ["kms:Decrypt"]
    resources = [module.secrets_key.arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${data.aws_region.current.region}.amazonaws.com"]
    }
  }
}

module "external_secrets_identity" {
  source          = "../../modules/workload-identity"
  name            = "${local.name}-external-secrets"
  cluster_name    = module.cluster.cluster_name
  namespace       = "external-secrets"
  service_account = "external-secrets"
  policy_json     = data.aws_iam_policy_document.external_secrets.json
  tags            = local.tags
}
