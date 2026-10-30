output "primary_endpoint" { value = aws_elasticache_replication_group.this.primary_endpoint_address }
output "auth_token" {
  value     = random_password.auth_token.result
  sensitive = true
}
