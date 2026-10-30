module "platform" {
  source = "../../stacks/platform"

  environment         = "staging"
  vpc_cidr            = "10.20.0.0/16"
  availability_zones  = var.availability_zones
  node_instance_types = ["m7i.large"]
  node_min_size       = 2
  node_max_size       = 6
  db_instance_class   = "db.m7g.large"
  bucket_suffix       = var.bucket_suffix
}

output "cluster_name" { value = module.platform.cluster_name }
output "documents_bucket" { value = module.platform.documents_bucket }
