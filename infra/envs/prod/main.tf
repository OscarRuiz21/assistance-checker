locals {
  name_prefix = "assistance-prod"
  tags = {
    Project     = "assistance-checker"
    Environment = "prod"
  }
}

module "network" {
  source = "../../modules/network"

  name_prefix          = local.name_prefix
  azs                  = var.azs
  public_subnet_cidrs  = ["10.30.0.0/20", "10.30.16.0/20"]
  private_subnet_cidrs = ["10.30.128.0/20", "10.30.144.0/20"]
  single_nat_gateway   = false # prod: un NAT por AZ, evita punto único de falla
  tags                 = local.tags
}

module "database" {
  source = "../../modules/database"

  name_prefix           = local.name_prefix
  vpc_id                = module.network.vpc_id
  private_subnet_ids    = module.network.private_subnet_ids
  security_group_id     = module.network.database_security_group_id
  instance_class        = "db.r6g.large"
  multi_az              = true
  backup_retention_days = 35
  deletion_protection   = true
  alarm_sns_topic_arn   = var.alarm_sns_topic_arn
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
  desired_count   = 2 # HA mínima entre 2 AZs
  task_cpu        = "1024"
  task_memory     = "2048"
  spring_profile  = "prod"
  cors_origins    = var.cors_origins
  auth_issuer_uri = var.auth_issuer_uri

  db_url                           = "jdbc:postgresql://${module.database.endpoint}/${module.database.db_name}"
  db_migration_user                = module.database.master_username
  db_migration_password_secret_arn = module.database.master_user_secret_arn
  db_migration_password_is_json_kv = true # RDS managed master secret es JSON {"username":..,"password":..}
  db_app_password_secret_arn       = module.database.db_app_password_secret_arn
  app_pii_key_secret_arn           = module.database.app_pii_key_secret_arn

  enable_deployment_circuit_breaker = true

  github_oidc_enabled      = true
  github_repository        = var.github_repository
  github_oidc_allowed_refs = ["refs/heads/main", "refs/tags/*"]

  alarm_sns_topic_arn = var.alarm_sns_topic_arn

  tags = local.tags
}
