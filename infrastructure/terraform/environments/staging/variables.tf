# ADR-0003: until the hosting decision is made, environments that hold real client data stay in Indonesia (UU PDP,
# #163). Change this together with the ADR.
variable "aws_region" {
  type = string
  validation {
    condition     = var.aws_region == "ap-southeast-3"
    error_message = "Use ap-southeast-3 (Jakarta) for this environment until ADR-0003 decides otherwise."
  }
}
variable "availability_zones" { type = list(string) }
variable "bucket_suffix" { type = string }
