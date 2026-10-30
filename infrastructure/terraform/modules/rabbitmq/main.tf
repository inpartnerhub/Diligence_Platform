resource "random_password" "broker" {
  length  = 32
  special = false
}

resource "aws_security_group" "this" {
  name        = "${var.name}-rabbitmq"
  description = "RabbitMQ (AMQPS) access for ${var.name} application nodes"
  vpc_id      = var.vpc_id
  tags        = var.tags

  ingress {
    description     = "RabbitMQ (AMQPS) from application nodes"
    from_port       = 5671
    to_port         = 5671
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }
}

# Amazon MQ supports audit logs only for the ActiveMQ engine; for RabbitMQ only general logs exist (enabled below).
#trivy:ignore:AWS-0070
resource "aws_mq_broker" "this" {
  broker_name                = var.name
  engine_type                = "RabbitMQ"
  engine_version             = var.engine_version
  host_instance_type         = var.instance_type
  deployment_mode            = var.cluster_mode ? "CLUSTER_MULTI_AZ" : "SINGLE_INSTANCE"
  subnet_ids                 = var.cluster_mode ? var.subnet_ids : [var.subnet_ids[0]]
  security_groups            = [aws_security_group.this.id]
  publicly_accessible        = false
  auto_minor_version_upgrade = true

  user {
    username = "diligence"
    password = random_password.broker.result
  }

  logs { general = true }
  tags = var.tags
}
