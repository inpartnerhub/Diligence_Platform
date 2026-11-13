variable "environment" {
  type = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging or prod."
  }
}
variable "vpc_cidr" { type = string }
variable "availability_zones" { type = list(string) }
variable "node_instance_types" { type = list(string) }
variable "node_min_size" { type = number }
variable "node_max_size" { type = number }
variable "db_instance_class" { type = string }
variable "bucket_suffix" {
  type        = string
  description = "Globally unique suffix for S3 bucket names (e.g. AWS account ID)."
}
variable "tags" {
  type    = map(string)
  default = {}
}

variable "github_repository" {
  description = "owner/name of the repository whose deploy workflow may assume the deploy role (#164)"
  type        = string
  default     = "inpartnerhub/Diligence_Platform"
}
