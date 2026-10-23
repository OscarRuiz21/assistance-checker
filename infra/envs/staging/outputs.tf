output "alb_dns_name" {
  value = module.app.alb_dns_name
}

output "ecr_repository_url" {
  value = module.app.ecr_repository_url
}

output "db_endpoint" {
  value = module.database.endpoint
}

output "github_deploy_role_arn" {
  value       = module.app.github_deploy_role_arn
  description = "Configurar como secrets.AWS_DEPLOY_ROLE_ARN_STAGING en GitHub."
}
