output "primary_endpoint" { value = aws_elasticache_replication_group.this.primary_endpoint_address }
# Credentials per workload (#171): backend, web (BFF).
output "users" {
  value = {
    for name in ["backend", "web"] :
    name => { username = aws_elasticache_user.this[name].user_name, password = random_password.user[name].result }
  }
  sensitive = true
}
