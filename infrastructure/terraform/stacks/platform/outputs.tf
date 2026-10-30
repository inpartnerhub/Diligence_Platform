output "cluster_name" { value = module.cluster.cluster_name }
output "documents_bucket" { value = module.object_storage.bucket_name }
output "database_master_secret_arn" { value = module.postgresql.master_user_secret_arn }
