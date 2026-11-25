terraform {
  required_version = ">= 1.10"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 6.0" }
    random = { source = "hashicorp/random", version = "~> 3.7" }
  }

  # Partial configuration: bucket/region are supplied with -backend-config=backend.hcl (not committed).
  backend "s3" {
    key          = "diligence/dev/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = { project = "diligence", environment = "dev" }
  }
}
