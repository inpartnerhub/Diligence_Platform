module "platform" {
  source = "../../stacks/platform"

  environment         = "prod"
  vpc_cidr            = "10.30.0.0/16"
  availability_zones  = var.availability_zones
  node_instance_types = ["m7i.xlarge"]
  node_min_size       = 3
  node_max_size       = 12
  db_instance_class   = "db.r7g.xlarge"
  bucket_suffix       = var.bucket_suffix
}

output "cluster_name" { value = module.platform.cluster_name }
output "documents_bucket" { value = module.platform.documents_bucket }
