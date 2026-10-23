output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

output "ecr_repository_url" {
  value = aws_ecr_repository.this.repository_url
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "ecs_service_name" {
  value = aws_ecs_service.app.name
}

output "task_definition_arn" {
  value = aws_ecs_task_definition.app.arn
}

output "github_deploy_role_arn" {
  value = var.github_oidc_enabled ? aws_iam_role.github_deploy[0].arn : null
}

output "log_group_name" {
  value = aws_cloudwatch_log_group.app.name
}
