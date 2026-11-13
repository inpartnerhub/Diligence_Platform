variable "name" {
  description = "IAM role name"
  type        = string
}

variable "cluster_name" { type = string }
variable "namespace" { type = string }
variable "service_account" { type = string }

variable "policy_json" {
  description = "Inline IAM policy granted to the workload (least privilege)"
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
