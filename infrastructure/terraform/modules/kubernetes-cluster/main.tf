module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 21.0"

  name                   = var.name
  kubernetes_version     = var.kubernetes_version
  vpc_id                 = var.vpc_id
  subnet_ids             = var.subnet_ids
  endpoint_public_access = var.endpoint_public_access

  enable_cluster_creator_admin_permissions = true

  # All control plane log types, encrypted and retained (PRD 10.1 audit, AWS-0038).
  enabled_log_types                      = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
  cloudwatch_log_group_retention_in_days = var.log_retention_days
  cloudwatch_log_group_kms_key_id        = var.log_kms_key_arn

  addons = {
    coredns                = {}
    kube-proxy             = {}
    vpc-cni                = { before_compute = true }
    eks-pod-identity-agent = { before_compute = true }
  }

  eks_managed_node_groups = {
    general = {
      instance_types = var.node_instance_types
      min_size       = var.node_min_size
      max_size       = var.node_max_size
      desired_size   = var.node_min_size
    }
  }

  tags = var.tags
}
