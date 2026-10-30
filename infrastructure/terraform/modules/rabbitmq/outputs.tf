output "amqps_endpoint" { value = aws_mq_broker.this.instances[0].endpoints[0] }
output "username" { value = "platform" }
output "password" {
  value     = random_password.broker.result
  sensitive = true
}
