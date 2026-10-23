# Ambiente "demo" — mínimo viable para mostrar el producto a prospectos,
# NO apto para datos reales de empleados (ver docs/16, Fase 0/3).
#
# Decisión: en vez de Lightsail (stack separado, sin reutilizar los módulos
# de network/database/app ni el pipeline de deploy.yml), este ambiente
# reutiliza los MISMOS módulos Terraform con el tamaño más pequeño posible:
# 1 AZ lógica para NAT (igual single_nat_gateway=true), RDS t4g.micro sin
# Multi-AZ, 1 sola task Fargate 512/1024. Esto evita mantener dos paths de
# despliegue distintos (Lightsail vs ECS) por un ahorro de costo marginal.
# Si el presupuesto de demo es muy ajustado, Lightsail Containers es la
# alternativa más barata — queda documentada aquí pero no implementada.

locals {
  name_prefix = "assistance-demo"
  tags = {
    Project     = "assistance-checker"
    Environment = "demo"
  }
}

module "network" {
  source = "../../modules/network"

  name_prefix          = local.name_prefix
  azs                  = ["us-east-1a", "us-east-1b"]
  public_subnet_cidrs  = ["10.50.0.0/20", "10.50.16.0/20"]
  private_subnet_cidrs = ["10.50.128.0/20", "10.50.144.0/20"]
  single_nat_gateway   = true
  tags                 = local.tags
}

module "database" {
  source = "../../modules/database"

  name_prefix           = local.name_prefix
  vpc_id                = module.network.vpc_id
  private_subnet_ids    = module.network.private_subnet_ids
  security_group_id     = module.network.database_security_group_id
  instance_class        = "db.t4g.micro"
  multi_az              = false
  backup_retention_days = 7
  deletion_protection   = false
  tags                  = local.tags
}

module "app" {
  source = "../../modules/app"

  name_prefix           = local.name_prefix
  vpc_id                = module.network.vpc_id
  public_subnet_ids     = module.network.public_subnet_ids
  private_subnet_ids    = module.network.private_subnet_ids
  alb_security_group_id = module.network.alb_security_group_id
  app_security_group_id = module.network.app_security_group_id
  acm_certificate_arn   = var.acm_certificate_arn

  container_image = var.container_image
  desired_count   = 1
  task_cpu        = "512"
  task_memory     = "1024"
  spring_profile  = "demo"
  cors_origins    = var.cors_origins

  db_url                           = "jdbc:postgresql://${module.database.endpoint}/${module.database.db_name}"
  db_migration_user                = module.database.master_username
  db_migration_password_secret_arn = module.database.master_user_secret_arn
  db_migration_password_is_json_kv = true
  db_app_password_secret_arn       = module.database.db_app_password_secret_arn
  app_pii_key_secret_arn           = module.database.app_pii_key_secret_arn

  enable_deployment_circuit_breaker = false
  github_oidc_enabled               = false # demo se despliega manualmente, no vía GH Actions por ahora

  tags = local.tags
}
