# Node root volumes hold the pods' emptyDir volumes, e.g. uploaded documents during ingestion (#176): encrypted with
# a customer-managed key, like documents and the database.
module "node_volume_key" {
  source                = "../kms-key"
  alias                 = "${var.name}-node-volumes"
  description           = "Encryption key for the ${var.name} EKS node volumes"
  allow_ec2_autoscaling = true
  tags                  = var.tags
}

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
    coredns    = {}
    kube-proxy = {}
    # Network policy enforcement (#157): without it every NetworkPolicy in infrastructure/kubernetes (default deny,
    # backend only from NGINX Ingress / frontend, the diligence-processing isolation) is accepted by the API server
    # but not applied.
    vpc-cni = {
      before_compute       = true
      configuration_values = jsonencode({ enableNetworkPolicy = "true" })
    }
    eks-pod-identity-agent = { before_compute = true }
  }

  eks_managed_node_groups = merge(
    {
      general = {
        instance_types = var.node_instance_types
        min_size       = var.node_min_size
        max_size       = var.node_max_size
        desired_size   = var.node_min_size
        # IMDSv2 with one hop: pods cannot reach the node's instance profile, so workloads get AWS access only through
        # their own Pod Identity role (#158).
        metadata_options      = local.node_metadata_options
        block_device_mappings = local.node_block_device_mappings
      }
    },
    # Optional Spot pool for the stateless, retriable document-processing workloads (ClamAV, Tika, Gotenberg, the
    # ingestion worker). It is tainted, so only pods that tolerate it land here; those pods only *prefer* Spot and fall
    # back to the general on-demand pool when Spot capacity is unavailable, so an interruption never stops ingestion.
    # Empty var.spot_instance_types (dev/staging default) leaves this pool out entirely.
    length(var.spot_instance_types) > 0 ? {
      spot = {
        instance_types = var.spot_instance_types
        capacity_type  = "SPOT"
        min_size       = var.spot_min_size
        max_size       = var.spot_max_size
        desired_size   = var.spot_min_size
        labels         = { "diligence.io/capacity-type" = "spot" }
        taints = {
          spot = {
            key    = "diligence.io/workload"
            value  = "burstable"
            effect = "NO_SCHEDULE"
          }
        }
        metadata_options      = local.node_metadata_options
        block_device_mappings = local.node_block_device_mappings
      }
    } : {}
  )

  tags = var.tags
}

# Shared by both managed node groups so the Spot pool inherits the same hardening (IMDSv2 one-hop, CMK-encrypted root).
locals {
  node_metadata_options = {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }
  node_block_device_mappings = {
    root = {
      device_name = "/dev/xvda"
      ebs = {
        volume_size           = 50
        volume_type           = "gp3"
        encrypted             = true
        kms_key_id            = module.node_volume_key.arn
        delete_on_termination = true
      }
    }
  }
}
