variable "name" { type = string }
variable "cidr" { type = string }
variable "azs" { type = list(string) }
variable "single_nat_gateway" {
  type        = bool
  default     = false
  description = "Cheaper single NAT for non-production environments."
}
variable "tags" {
  type    = map(string)
  default = {}
}
variable "log_kms_key_arn" {
  type        = string
  description = "KMS key for the flow log CloudWatch log group."
}
variable "log_retention_days" {
  type    = number
  default = 365
}
variable "enable_interface_endpoints" {
  type        = bool
  default     = false
  description = "Provision ECR, STS and CloudWatch Logs interface VPC endpoints. The S3 gateway endpoint is always enabled (free); interface endpoints charge hourly per AZ and are opt-in for high-traffic scale."
}
