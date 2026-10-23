variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  description = "Subnets privadas para el DB subnet group (sin acceso público)."
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group que ya permite el puerto 5432 solo desde la app."
  type        = string
}

variable "instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "allocated_storage_gb" {
  type    = number
  default = 20
}

variable "max_allocated_storage_gb" {
  description = "Límite de autoescalado de storage (gp3)."
  type        = number
  default     = 100
}

variable "multi_az" {
  description = "Multi-AZ real (standby síncrono). true en prod, false en staging/demo para ahorrar costo."
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  description = "Días de retención de backups automáticos (7-35, PITR)."
  type        = number
  default     = 7

  validation {
    condition     = var.backup_retention_days >= 7 && var.backup_retention_days <= 35
    error_message = "backup_retention_days debe estar entre 7 y 35 para cumplir el requisito de PITR del runbook (docs/11)."
  }
}

variable "deletion_protection" {
  description = "true en prod: bloquea `terraform destroy` / consola sobre la instancia."
  type        = bool
  default     = false
}

variable "database_name" {
  type    = string
  default = "assistance"
}

variable "master_username" {
  description = "Usuario owner/superuser (DB_MIGRATION_USER) que corre Flyway."
  type        = string
  default     = "assistance"
}

variable "master_password_secret_arn" {
  description = "ARN del secreto en Secrets Manager con la contraseña del usuario de migración. Si se deja vacío, Terraform genera y gestiona la contraseña (manage_master_user_password)."
  type        = string
  default     = ""
}

variable "alarm_sns_topic_arn" {
  description = "ARN del SNS topic para alarmas (vacío = alarma sin acción, solo visible en consola)."
  type        = string
  default     = ""
}

variable "tags" {
  type    = map(string)
  default = {}
}
