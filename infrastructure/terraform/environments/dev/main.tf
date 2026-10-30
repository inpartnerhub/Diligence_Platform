module "platform" {
  source = "../../stacks/platform"

  environment         = "dev"
  vpc_cidr            = "10.10.0.0/16"
  availability_zones  = var.availability_zones
  node_instance_types = ["t3.large"]
  node_min_size       = 2
  node_max_size       = 4
  db_instance_class   = "db.t4g.medium"
  bucket_suffix       = var.bucket_suffix
}

output "cluster_name" { value = module.platform.cluster_name }
output "documents_bucket" { value = module.platform.documents_bucket }
