variable "name" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "allowed_security_group_ids" { type = list(string) }
variable "database_name" {
  type    = string
  default = "diligence"
}
variable "engine_version" {
  type    = string
  default = "17"
}
variable "instance_class" {
  type    = string
  default = "db.t4g.medium"
}
variable "allocated_storage_gb" {
  type    = number
  default = 50
}
variable "multi_az" {
  type    = bool
  default = true
}
variable "backup_retention_days" {
  type    = number
  default = 14
}
variable "deletion_protection" {
  type        = bool
  default     = true
  description = "Keep enabled in every environment; disable explicitly (and temporarily) only to tear an environment down."
}
variable "tags" {
  type    = map(string)
  default = {}
}
