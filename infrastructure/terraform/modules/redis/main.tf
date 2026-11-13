# One RBAC user per workload, restricted by key pattern (#171): the backend, the web app (BFF sessions, which hold
# tokens) and Kong (rate-limit counters) cannot read each other's keys. Access strings: access-strings.json, also read
# by RedisAccessStringsIntegrationTest. ElastiCache requires a user named "default" in the group; it is disabled.
locals {
  access_strings = jsondecode(file("${path.module}/access-strings.json"))
  users          = toset(["default", "backend", "web", "gateway"])
}

resource "random_password" "user" {
  for_each = local.users
  length   = 48
  special  = false
}

resource "aws_elasticache_user" "this" {
  for_each      = local.users
  user_id       = "${var.name}-${each.key}"
  user_name     = each.key
  engine        = "valkey"
  access_string = local.access_strings[each.key]

  authentication_mode {
    type      = "password"
    passwords = [random_password.user[each.key].result]
  }

  tags = var.tags
}

resource "aws_elasticache_user_group" "this" {
  user_group_id = var.name
  engine        = "valkey"
  user_ids      = [for user in aws_elasticache_user.this : user.user_id]
  tags          = var.tags
}

resource "aws_elasticache_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
}

resource "aws_security_group" "this" {
  name        = "${var.name}-redis"
  description = "Redis access for ${var.name} application nodes"
  vpc_id      = var.vpc_id
  tags        = var.tags

  ingress {
    description     = "Redis from application nodes"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.name
  description          = "Diligence cache"
  engine               = "valkey"
  engine_version       = var.engine_version
  node_type            = var.node_type
  num_cache_clusters   = var.num_cache_clusters
  port                 = 6379

  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.this.id]
  automatic_failover_enabled = var.num_cache_clusters > 1
  multi_az_enabled           = var.num_cache_clusters > 1
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  # RBAC replaces the shared AUTH token (#171); DELETE removes the token from groups created before.
  user_group_ids             = [aws_elasticache_user_group.this.user_group_id]
  auth_token_update_strategy = "DELETE"

  tags = var.tags
}
