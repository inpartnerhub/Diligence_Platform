output "endpoint" { value = "https://${aws_opensearch_domain.this.endpoint}" }
output "domain_arn" { value = aws_opensearch_domain.this.arn }
output "master_user_name" { value = var.master_user_name }
output "master_user_password" {
  value     = random_password.master.result
  sensitive = true
}
