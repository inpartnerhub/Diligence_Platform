# Customer-managed key for storage and Performance Insights (AWS-0078).
module "kms" {
  source      = "../kms-key"
  alias       = "${var.name}-postgresql"
  description = "Encryption key for the ${var.name} PostgreSQL database"
  tags        = var.tags
}

resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "this" {
  name        = "${var.name}-postgresql"
  description = "PostgreSQL access for ${var.name} application nodes"
  vpc_id      = var.vpc_id
  tags        = var.tags

  ingress {
    description     = "PostgreSQL from application nodes"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }
}

resource "aws_db_instance" "this" {
  identifier     = var.name
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.allocated_storage_gb * 5
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = module.kms.arn

  db_name  = var.database_name
  username = "diligence_admin"
  # Master password is generated and rotated by RDS in Secrets Manager; never in Terraform state.
  manage_master_user_password = true
  # IAM database authentication allows short-lived tokens instead of static passwords (AWS-0176).
  iam_database_authentication_enabled = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  multi_az               = var.multi_az
  publicly_accessible    = false

  backup_retention_period         = var.backup_retention_days
  deletion_protection             = var.deletion_protection
  skip_final_snapshot             = !var.deletion_protection
  final_snapshot_identifier       = var.deletion_protection ? "${var.name}-final" : null
  performance_insights_enabled    = true
  performance_insights_kms_key_id = module.kms.arn
  enabled_cloudwatch_logs_exports = ["postgresql"]
  auto_minor_version_upgrade      = true

  tags = var.tags
}
