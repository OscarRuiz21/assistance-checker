variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "azs" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b"]
}

variable "acm_certificate_arn" {
  description = "ARN del certificado ACM para app.<dominio> en producción."
  type        = string
}

variable "container_image" {
  description = "Imagen inicial (ECR). CI actualiza revisiones de la task definition después."
  type        = string
}

variable "cors_origins" {
  type    = string
  default = "https://app.assistance-checker.example.com"
}

variable "auth_issuer_uri" {
  description = "Cognito user pool issuer URI de producción."
  type        = string
}

variable "github_repository" {
  type    = string
  default = "biomex/assistance-checker"
}

variable "alarm_sns_topic_arn" {
  type    = string
  default = ""
}
