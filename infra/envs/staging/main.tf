locals {
  name_prefix = "assistance-staging"
  tags = {
    Project     = "assistance-checker"
    Environment = "staging"
  }
}

module "network" {
  source = "../../modules/network"

  name_prefix          = local.name_prefix
  azs                  = var.azs
  public_subnet_cidrs  = ["10.40.0.0/20", "10.40.16.0/20"]
  private_subnet_cidrs = ["10.40.128.0/20", "10.40.144.0/20"]
  single_nat_gateway   = true # staging: costo optimizado, un solo NAT
  tags                 = local.tags
}

module "database" {
  source = "../../modules/database"

  name_prefix           = local.name_prefix
  vpc_id                = module.network.vpc_id
  private_subnet_ids    = module.network.private_subnet_ids
  security_group_id     = module.network.database_security_group_id
  instance_class        = "db.t4g.micro"
  multi_az              = false # staging no requiere HA real
  backup_retention_days = 7
  deletion_protection   = false
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
  desired_count   = 1
  task_cpu        = "512"
  task_memory     = "1024"
  spring_profile  = var.spring_profile
  cors_origins    = var.cors_origins
  auth_issuer_uri = var.auth_issuer_uri

  db_url                           = "jdbc:postgresql://${module.database.endpoint}/${module.database.db_name}"
  db_migration_user                = module.database.master_username
  db_migration_password_secret_arn = module.database.master_user_secret_arn
  db_migration_password_is_json_kv = true
  db_app_password_secret_arn       = module.database.db_app_password_secret_arn
  app_pii_key_secret_arn           = module.database.app_pii_key_secret_arn

  enable_deployment_circuit_breaker = true

  github_oidc_enabled      = true
  github_repository        = var.github_repository
  github_oidc_allowed_refs = ["refs/heads/main"]

  alarm_sns_topic_arn = var.alarm_sns_topic_arn

  tags = local.tags
}
