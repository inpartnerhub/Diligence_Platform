module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.0"

  name            = var.name
  cidr            = var.cidr
  azs             = var.azs
  private_subnets = [for i, _ in var.azs : cidrsubnet(var.cidr, 4, i)]
  public_subnets  = [for i, _ in var.azs : cidrsubnet(var.cidr, 8, 200 + i)]

  enable_nat_gateway   = true
  single_nat_gateway   = var.single_nat_gateway
  enable_dns_hostnames = true

  # VPC flow logs for all traffic, encrypted with the platform log key.
  enable_flow_log                                 = true
  create_flow_log_cloudwatch_log_group            = true
  create_flow_log_cloudwatch_iam_role             = true
  flow_log_traffic_type                           = "ALL"
  flow_log_max_aggregation_interval               = 60
  flow_log_cloudwatch_log_group_retention_in_days = var.log_retention_days
  flow_log_cloudwatch_log_group_kms_key_id        = var.log_kms_key_arn

  public_subnet_tags  = { "kubernetes.io/role/elb" = 1 }
  private_subnet_tags = { "kubernetes.io/role/internal-elb" = 1 }
  tags                = var.tags
}
