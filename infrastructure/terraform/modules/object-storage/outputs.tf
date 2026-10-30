output "bucket_name" { value = aws_s3_bucket.documents.bucket }
output "bucket_arn" { value = aws_s3_bucket.documents.arn }
output "kms_key_arn" { value = module.kms.arn }
output "access_log_bucket_name" { value = aws_s3_bucket.access_logs.bucket }
