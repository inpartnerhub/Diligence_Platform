# Composition of the Diligence Platform's cloud resources for one environment.
# Generated credentials are written to Vault (kv/diligence/<env>/backend/*) and synced into Kubernetes by the
# Vault Secrets Operator — see infrastructure/kubernetes/base/backend/vault-secret.yaml.

locals {
  name = "diligence-${var.environment}"
  tags = merge(var.tags, { project = "diligence", environment = var.environment, managed-by = "terraform" })
}

# Shared customer-managed key for CloudWatch log groups (VPC flow logs, EKS control plane, OpenSearch).
module "log_key" {
  source                = "../../modules/kms-key"
  alias                 = "${local.name}-logs"
  description           = "Encryption key for ${local.name} CloudWatch log groups"
  allow_cloudwatch_logs = true
  tags                  = local.tags
}

module "network" {
  source             = "../../modules/network"
  name               = local.name
  cidr               = var.vpc_cidr
  azs                = var.availability_zones
  single_nat_gateway = var.environment != "prod"
  log_kms_key_arn    = module.log_key.arn
  tags               = local.tags
}

module "cluster" {
  source              = "../../modules/kubernetes-cluster"
  name                = local.name
  vpc_id              = module.network.vpc_id
  subnet_ids          = module.network.private_subnet_ids
  node_instance_types = var.node_instance_types
  node_min_size       = var.node_min_size
  node_max_size       = var.node_max_size
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
  multi_az                   = var.environment == "prod"
  tags                       = local.tags
}

module "redis" {
  source                     = "../../modules/redis"
  name                       = local.name
  vpc_id                     = module.network.vpc_id
  subnet_ids                 = module.network.private_subnet_ids
  allowed_security_group_ids = [module.cluster.node_security_group_id]
  num_cache_clusters         = var.environment == "prod" ? 2 : 1
  tags                       = local.tags
}

module "rabbitmq" {
  source                     = "../../modules/rabbitmq"
  name                       = local.name
  vpc_id                     = module.network.vpc_id
  subnet_ids                 = module.network.private_subnet_ids
  allowed_security_group_ids = [module.cluster.node_security_group_id]
  cluster_mode               = var.environment == "prod"
  instance_type              = var.environment == "prod" ? "mq.m7g.large" : "mq.m7g.medium"
  tags                       = local.tags
}

module "opensearch" {
  source                     = "../../modules/opensearch"
  name                       = local.name
  vpc_id                     = module.network.vpc_id
  subnet_ids                 = module.network.private_subnet_ids
  allowed_security_group_ids = [module.cluster.node_security_group_id]
  instance_count             = var.environment == "prod" ? 3 : 1
  instance_type              = var.environment == "prod" ? "r7g.large.search" : "t3.medium.search"
  log_kms_key_arn            = module.log_key.arn
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

# Terraform owns kv/diligence/<env>/backend/infrastructure exclusively (a KV v2 write replaces the whole secret).
# Operator-managed values (DB_USERNAME, DB_PASSWORD, SENTRY_DSN) live in kv/diligence/<env>/backend/application.
resource "vault_kv_secret_v2" "backend_infrastructure" {
  mount = "kv"
  name  = "diligence/${var.environment}/backend/infrastructure"
  data_json = jsonencode({
    DB_URL              = "jdbc:postgresql://${module.postgresql.endpoint}:${module.postgresql.port}/${module.postgresql.database_name}?sslmode=verify-full"
    REDIS_HOST          = module.redis.primary_endpoint
    REDIS_PASSWORD      = module.redis.auth_token
    RABBITMQ_HOST       = trimprefix(split(":", module.rabbitmq.amqps_endpoint)[1], "//")
    RABBITMQ_USERNAME   = module.rabbitmq.username
    RABBITMQ_PASSWORD   = module.rabbitmq.password
    OPENSEARCH_URL      = module.opensearch.endpoint
    OPENSEARCH_USERNAME = module.opensearch.backend_user_name
    OPENSEARCH_PASSWORD = module.opensearch.backend_user_password
  })
}

# The OpenSearch master credential is read only by the opensearch-bootstrap Job (Vault role
# diligence-opensearch-bootstrap), which creates the backend's least-privilege user above (#159).
resource "vault_kv_secret_v2" "opensearch_admin" {
  mount = "kv"
  name  = "diligence/${var.environment}/opensearch-admin"
  data_json = jsonencode({
    OPENSEARCH_URL             = module.opensearch.endpoint
    OPENSEARCH_MASTER_USERNAME = module.opensearch.master_user_name
    OPENSEARCH_MASTER_PASSWORD = module.opensearch.master_user_password
    OPENSEARCH_APP_USERNAME    = module.opensearch.backend_user_name
    OPENSEARCH_APP_PASSWORD    = module.opensearch.backend_user_password
    OPENSEARCH_INDEX_PREFIX    = "diligence"
  })
}
