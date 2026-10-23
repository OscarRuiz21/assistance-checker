terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Backend remoto recomendado (S3 + DynamoDB lock). Comentado porque no
  # existe aún el bucket/tabla en esta cuenta; descomentar y rellenar tras
  # crearlos una sola vez (ver docs/17-ci-cd-y-despliegue.md § Terraform
  # backend bootstrap). Mientras tanto, el estado es local (no apto para
  # equipos, solo para validar `plan`/`apply` en solitario).
  #
  # backend "s3" {
  #   bucket         = "assistance-checker-tfstate-prod"
  #   key            = "prod/terraform.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "assistance-checker-tfstate-lock"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "assistance-checker"
      Environment = "prod"
      ManagedBy   = "terraform"
    }
  }
}
