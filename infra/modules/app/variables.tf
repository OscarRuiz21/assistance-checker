variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "alb_security_group_id" {
  type = string
}

variable "app_security_group_id" {
  type = string
}

variable "acm_certificate_arn" {
  description = "ARN del certificado ACM (us-east-1 o misma región del ALB) para el listener HTTPS."
  type        = string
}

variable "container_image" {
  description = "Imagen completa (registry/repo:tag) a desplegar. CI la actualiza por SHA en cada deploy."
  type        = string
}

variable "container_port" {
  type    = number
  default = 8080
}

variable "management_port" {
  type    = number
  default = 8081
}

variable "task_cpu" {
  description = "CPU units de Fargate (256, 512, 1024, ...)."
  type        = string
  default     = "512"
}

variable "task_memory" {
  description = "Memoria MiB de Fargate."
  type        = string
  default     = "1024"
}

variable "desired_count" {
  description = "Tasks deseadas. 2 en prod (HA entre AZs), 1 en staging/demo."
  type        = number
  default     = 1
}

variable "spring_profile" {
  description = "Valor de SPRING_PROFILES_ACTIVE para este ambiente (demo | prod)."
  type        = string
}

variable "cors_origins" {
  type = string
}

variable "db_url" {
  type = string
}

variable "db_migration_user" {
  type = string
}

variable "db_migration_password_secret_arn" {
  type = string
}

variable "db_migration_password_is_json_kv" {
  description = "true si el secreto es un JSON {\"username\":..,\"password\":..} (caso de manage_master_user_password de RDS) y se debe extraer solo la clave 'password'."
  type        = bool
  default     = false
}

variable "db_app_user" {
  type    = string
  default = "assistance_app"
}

variable "db_app_password_secret_arn" {
  type = string
}

variable "app_pii_key_secret_arn" {
  type = string
}

variable "auth_issuer_uri" {
  description = "Cognito issuer URI. Vacío en demo (usa APP_JWT_SECRET en su lugar, fuera de este módulo)."
  type        = string
  default     = ""
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "enable_deployment_circuit_breaker" {
  description = "Rollback automático de ECS si el deployment no estabiliza (recomendado true en prod)."
  type        = bool
  default     = true
}

variable "alarm_sns_topic_arn" {
  type    = string
  default = ""
}

variable "github_oidc_enabled" {
  description = "Si true, crea el rol IAM asumible por GitHub Actions vía OIDC para este ambiente."
  type        = bool
  default     = false
}

variable "github_repository" {
  description = "owner/repo de GitHub, usado para restringir el trust policy del rol OIDC."
  type        = string
  default     = ""
}

variable "github_oidc_allowed_refs" {
  description = "Refs de git permitidos para asumir el rol (ej. ['refs/heads/main', 'refs/tags/*'])."
  type        = list(string)
  default     = ["refs/heads/main"]
}

variable "tags" {
  type    = map(string)
  default = {}
}
