output "endpoint" {
  value = aws_db_instance.this.endpoint
}

output "address" {
  value = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "db_name" {
  value = aws_db_instance.this.db_name
}

output "master_username" {
  value = aws_db_instance.this.username
}

output "master_user_secret_arn" {
  description = "ARN del secreto gestionado por RDS con la contraseña del usuario de migración (si manage_master_user_password=true)."
  value       = try(aws_db_instance.this.master_user_secret[0].secret_arn, null)
}

output "db_app_password_secret_arn" {
  value = aws_secretsmanager_secret.db_app_password.arn
}

output "app_pii_key_secret_arn" {
  value = aws_secretsmanager_secret.app_pii_key.arn
}

output "kms_key_arn" {
  value = aws_kms_key.rds.arn
}
