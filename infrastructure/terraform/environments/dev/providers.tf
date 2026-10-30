terraform {
  required_version = ">= 1.10"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 6.0" }
    random = { source = "hashicorp/random", version = "~> 3.7" }
    vault  = { source = "hashicorp/vault", version = "~> 5.0" }
  }

  # Partial configuration: bucket/region are supplied with -backend-config=backend.hcl (not committed).
  backend "s3" {
    key          = "platform/dev/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = { project = "platform", environment = "dev" }
  }
}

# Authenticates with VAULT_ADDR + VAULT_TOKEN (or CI OIDC/JWT auth) from the environment.
provider "vault" {}
