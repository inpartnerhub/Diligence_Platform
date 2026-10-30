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
