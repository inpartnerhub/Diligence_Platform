variable "alias" {
  type        = string
  description = "Alias without the alias/ prefix."
}
variable "description" { type = string }
variable "allow_cloudwatch_logs" {
  type        = bool
  default     = false
  description = "Allow CloudWatch Logs in this account and region to use the key for log group encryption."
}
variable "allow_ec2_autoscaling" {
  type        = bool
  default     = false
  description = "Allow the EC2 Auto Scaling service-linked role to use the key for encrypted EBS volumes of node groups."
}
variable "tags" {
  type    = map(string)
  default = {}
}
