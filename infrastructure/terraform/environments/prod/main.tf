module "diligence" {
  source = "../../stacks/diligence"

  environment        = "prod"
  vpc_cidr           = "10.30.0.0/16"
  availability_zones = var.availability_zones

  # ------------------------------------------------------------------ early-scale (minimal cost) profile
  # Sized for the first production users: single-AZ RDS, one Redis node and one NAT gateway (high_availability = false).
  # Data is still safe and private — RDS keeps 14-day automated backups, deletion protection and KMS encryption; Redis
  # stays encrypted; documents live in versioned, encrypted S3. Nothing about security or features changes, only
  # redundancy.
  #
  # To scale up when load justifies it (ADR-0007): set high_availability = true (→ Multi-AZ RDS, 2 Redis nodes, one
  # NAT per AZ) and raise the sizes below, e.g. node_instance_types ["m7i.xlarge"] min 3 / max 12, db.r7g.large.
  high_availability = false

  node_instance_types = ["m7i.large"]
  node_min_size       = 2
  node_max_size       = 6
  db_instance_class   = "db.t4g.large" # 2 vCPU / 8 GiB Graviton; bump to db.r7g.large at scale

  # Spot pool for the tainted document-processing workloads (ClamAV/Tika/Gotenberg/ingestion). Diversified instance
  # types reduce the chance all capacity is reclaimed at once; the pods fall back to the on-demand pool regardless.
  spot_instance_types = ["m7i.large", "m6i.large", "c7i.large"]

  bucket_suffix = var.bucket_suffix
}

output "cluster_name" { value = module.diligence.cluster_name }
output "documents_bucket" { value = module.diligence.documents_bucket }
output "deploy_role_arn" { value = module.diligence.deploy_role_arn } # GitHub environment variable AWS_DEPLOY_ROLE_ARN
