variable "name" { type = string }
variable "kubernetes_version" {
  type    = string
  default = "1.34"
}
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "endpoint_public_access" {
  type    = bool
  default = false
}
variable "node_instance_types" {
  type    = list(string)
  default = ["m7i.large"]
}
variable "node_min_size" {
  type    = number
  default = 2
}
variable "node_max_size" {
  type    = number
  default = 6
}
variable "tags" {
  type    = map(string)
  default = {}
}
variable "log_kms_key_arn" {
  type        = string
  description = "KMS key for the control plane CloudWatch log group."
}
variable "log_retention_days" {
  type    = number
  default = 365
}
