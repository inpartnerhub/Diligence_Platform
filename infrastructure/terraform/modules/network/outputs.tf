output "vpc_id" { value = module.vpc.vpc_id }
output "vpc_cidr" { value = module.vpc.vpc_cidr_block }
output "private_subnet_ids" { value = module.vpc.private_subnets }
