# Composition of the platform's cloud resources for one environment.
# Generated credentials are written to Vault (kv/platform/<env>/backend/*) and synced into Kubernetes by the
# Vault Secrets Operator — see infrastructure/kubernetes/base/backend/vault-secret.yaml.

locals {
  name = "platform-${var.environment}"
  tags = merge(var.tags, { project = "platform", environment = var.environment, managed-by = "terraform" })
}

module "network" {
  source             = "../../modules/network"
  name               = local.name
  cidr               = var.vpc_cidr
  azs                = var.availability_zones
  single_nat_gateway = var.environment != "prod"
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
  deletion_protection        = var.environment == "prod"
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
  tags                       = local.tags
}

module "object_storage" {
  source      = "../../modules/object-storage"
  bucket_name = "${local.name}-documents-${var.bucket_suffix}"
  tags        = local.tags
}

# Terraform owns kv/platform/<env>/backend/infrastructure exclusively (a KV v2 write replaces the whole secret).
# Operator-managed values (DB_USERNAME, DB_PASSWORD, SENTRY_DSN) live in kv/platform/<env>/backend/application.
resource "vault_kv_secret_v2" "backend_infrastructure" {
  mount = "kv"
  name  = "platform/${var.environment}/backend/infrastructure"
  data_json = jsonencode({
    DB_URL            = "jdbc:postgresql://${module.postgresql.endpoint}:${module.postgresql.port}/${module.postgresql.database_name}?sslmode=verify-full"
    REDIS_HOST        = module.redis.primary_endpoint
    REDIS_PASSWORD    = module.redis.auth_token
    RABBITMQ_HOST     = trimprefix(split(":", module.rabbitmq.amqps_endpoint)[1], "//")
    RABBITMQ_USERNAME = module.rabbitmq.username
    RABBITMQ_PASSWORD = module.rabbitmq.password
    OPENSEARCH_URL    = module.opensearch.endpoint
  })
}
