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

# VPC endpoints keep high-volume AWS traffic off the NAT gateways: NAT data processing is billed per GB, and a
# due-diligence data room moves a lot of document bytes to and from S3. The S3 gateway endpoint is free and always
# enabled; the ECR, STS and CloudWatch Logs interface endpoints charge hourly per AZ and are opt-in via
# var.enable_interface_endpoints for high-traffic scale.

# Only the VPC may reach the interface endpoints, on HTTPS.
resource "aws_security_group" "endpoints" {
  count = var.enable_interface_endpoints ? 1 : 0

  name_prefix = "${var.name}-vpce-"
  description = "HTTPS from within the VPC to interface VPC endpoints"
  vpc_id      = module.vpc.vpc_id

  ingress {
    description = "HTTPS from the VPC"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [module.vpc.vpc_cidr_block]
  }

  tags = merge(var.tags, { Name = "${var.name}-vpce" })

  lifecycle { create_before_destroy = true }
}

module "vpc_endpoints" {
  source  = "terraform-aws-modules/vpc/aws//modules/vpc-endpoints"
  version = "~> 6.0"

  vpc_id = module.vpc.vpc_id

  endpoints = merge(
    {
      # Gateway endpoint: free, routed, carries all S3 (documents, previews) off the NAT.
      s3 = {
        service         = "s3"
        service_type    = "Gateway"
        route_table_ids = module.vpc.private_route_table_ids
        tags            = { Name = "${var.name}-s3" }
      }
    },
    var.enable_interface_endpoints ? {
      # Interface endpoints: private image pulls, Pod Identity (STS) and logs — opt-in for high-traffic scale.
      ecr_api = { service = "ecr.api", private_dns_enabled = true }
      ecr_dkr = { service = "ecr.dkr", private_dns_enabled = true }
      sts     = { service = "sts", private_dns_enabled = true }
      logs    = { service = "logs", private_dns_enabled = true }
    } : {}
  )

  # Interface endpoints live in the private subnets, reachable only on HTTPS from inside the VPC.
  subnet_ids         = var.enable_interface_endpoints ? module.vpc.private_subnets : []
  security_group_ids = var.enable_interface_endpoints ? [aws_security_group.endpoints[0].id] : []

  tags = var.tags
}
