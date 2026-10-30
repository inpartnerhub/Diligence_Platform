resource "aws_security_group" "this" {
  name        = "${var.name}-opensearch"
  description = "OpenSearch (HTTPS) access for ${var.name} application nodes"
  vpc_id      = var.vpc_id
  tags        = var.tags

  ingress {
    description     = "OpenSearch (HTTPS) from application nodes"
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }
}

locals {
  log_types = ["AUDIT_LOGS", "ES_APPLICATION_LOGS", "INDEX_SLOW_LOGS", "SEARCH_SLOW_LOGS"]
}

# Fine-grained access control: internal user database with a generated master user, published to Vault.
resource "random_password" "master" {
  length           = 32
  special          = true
  override_special = "!#%^*_-"
  min_upper        = 2
  min_lower        = 2
  min_numeric      = 2
  min_special      = 2
}

resource "aws_cloudwatch_log_group" "this" {
  for_each          = toset(local.log_types)
  name              = "/aws/opensearch/${var.name}/${lower(each.key)}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn
  tags              = var.tags
}

data "aws_iam_policy_document" "logs" {
  statement {
    actions   = ["logs:PutLogEvents", "logs:CreateLogStream"]
    resources = [for group in aws_cloudwatch_log_group.this : "${group.arn}:*"]
    principals {
      type        = "Service"
      identifiers = ["es.amazonaws.com"]
    }
  }
}

resource "aws_cloudwatch_log_resource_policy" "this" {
  policy_name     = "${var.name}-opensearch-logs"
  policy_document = data.aws_iam_policy_document.logs.json
}

resource "aws_opensearch_domain" "this" {
  domain_name    = var.name
  engine_version = var.engine_version

  cluster_config {
    instance_type          = var.instance_type
    instance_count         = var.instance_count
    zone_awareness_enabled = var.instance_count > 1
    dynamic "zone_awareness_config" {
      for_each = var.instance_count > 1 ? [1] : []
      content { availability_zone_count = min(var.instance_count, 3) }
    }
  }

  ebs_options {
    ebs_enabled = true
    volume_type = "gp3"
    volume_size = var.volume_size_gb
  }

  vpc_options {
    subnet_ids         = slice(var.subnet_ids, 0, min(var.instance_count, length(var.subnet_ids)))
    security_group_ids = [aws_security_group.this.id]
  }

  advanced_security_options {
    enabled                        = true
    anonymous_auth_enabled         = false
    internal_user_database_enabled = true
    master_user_options {
      master_user_name     = var.master_user_name
      master_user_password = random_password.master.result
    }
  }

  # Audit, application and slow logs to encrypted CloudWatch log groups (AWS-0042).
  dynamic "log_publishing_options" {
    for_each = aws_cloudwatch_log_group.this
    content {
      log_type                 = log_publishing_options.key
      cloudwatch_log_group_arn = log_publishing_options.value.arn
    }
  }

  encrypt_at_rest { enabled = true }
  node_to_node_encryption { enabled = true }
  domain_endpoint_options {
    enforce_https       = true
    tls_security_policy = "Policy-Min-TLS-1-2-2019-07"
  }

  tags = var.tags

  depends_on = [aws_cloudwatch_log_resource_policy.this]
}
