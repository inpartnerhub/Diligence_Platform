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
