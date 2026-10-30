variable "name" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "allowed_security_group_ids" { type = list(string) }
variable "engine_version" {
  type    = string
  default = "4.2"
}
variable "instance_type" {
  type    = string
  default = "mq.m7g.large"
}
variable "cluster_mode" {
  type    = bool
  default = true
}
variable "tags" {
  type    = map(string)
  default = {}
}
