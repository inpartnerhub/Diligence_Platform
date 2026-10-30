variable "bucket_name" { type = string }
variable "noncurrent_version_retention_days" {
  type    = number
  default = 90
}
variable "tags" {
  type    = map(string)
  default = {}
}
variable "access_log_retention_days" {
  type    = number
  default = 365
}
