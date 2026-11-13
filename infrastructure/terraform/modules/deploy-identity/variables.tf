variable "name" {
  description = "IAM role name"
  type        = string
}

variable "github_repository" {
  description = "owner/name of the repository whose deploy workflow may assume the role"
  type        = string
}

variable "github_environment" {
  description = "GitHub environment (dev, staging, prod) the deploy job must run in"
  type        = string
}

variable "cluster_name" { type = string }
variable "cluster_arn" { type = string }

variable "kubernetes_group" {
  description = "Kubernetes group the role is mapped to; RBAC in infrastructure/kubernetes/rbac grants its rights"
  type        = string
  default     = "diligence-deployer"
}

variable "tags" {
  type    = map(string)
  default = {}
}
