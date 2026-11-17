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

# High availability is a cost decision, not an environment one: default off keeps the early-scale footprint minimal
# (single-AZ RDS, a single-instance RabbitMQ broker, a one-node OpenSearch domain, one Redis node, one NAT gateway).
# Data durability and the security posture are unchanged either way — RDS keeps automated backups, deletion protection
# and KMS encryption; Redis and OpenSearch stay encrypted. Flip to true (and raise the instance sizes below) when load
# justifies it, and prod becomes Multi-AZ / clustered with no other changes.
variable "high_availability" {
  type    = bool
  default = false
}
# Optional Spot pool for document-processing workloads. Empty keeps every node on-demand (dev/staging default).
variable "spot_instance_types" {
  type    = list(string)
  default = []
}
variable "db_instance_class" { type = string }

# Managed-service sizing. Null keeps the default for the current high_availability mode; set these in an environment
# root to right-size without touching the stack.
variable "rabbitmq_instance_type" {
  type    = string
  default = null
}
variable "opensearch_instance_type" {
  type    = string
  default = null
}
variable "opensearch_instance_count" {
  type    = number
  default = null
}

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
