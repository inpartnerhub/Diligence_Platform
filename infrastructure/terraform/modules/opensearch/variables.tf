variable "name" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "allowed_security_group_ids" { type = list(string) }
variable "engine_version" {
  type    = string
  default = "OpenSearch_3.1"
}
variable "instance_type" {
  type    = string
  default = "r7g.large.search"
}
variable "instance_count" {
  type    = number
  default = 3
}
variable "volume_size_gb" {
  type    = number
  default = 100
}
variable "tags" {
  type    = map(string)
  default = {}
}
variable "master_user_name" {
  type    = string
  default = "diligence_admin"
}
variable "log_kms_key_arn" {
  type        = string
  description = "KMS key for the OpenSearch CloudWatch log groups."
}
variable "log_retention_days" {
  type    = number
  default = 365
}
