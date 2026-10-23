variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "azs" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b"]
}

variable "acm_certificate_arn" {
  type = string
}

variable "container_image" {
  type = string
}

variable "cors_origins" {
  type    = string
  default = "https://staging.assistance-checker.example.com"
}

variable "auth_issuer_uri" {
  description = "Vacío si staging corre con perfil demo (APP_JWT_SECRET) en vez de Cognito."
  type        = string
  default     = ""
}

variable "spring_profile" {
  description = "demo en staging por defecto (equivalente a datos sintéticos); cambiar a prod si staging valida Cognito real."
  type        = string
  default     = "demo"
}

variable "github_repository" {
  type    = string
  default = "biomex/assistance-checker"
}

variable "alarm_sns_topic_arn" {
  type    = string
  default = ""
}
