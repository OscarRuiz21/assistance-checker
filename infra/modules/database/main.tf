# RDS Postgres 16, cifrado KMS, backups con PITR, Multi-AZ configurable.
# Sin acceso público: solo en subnets privadas, security group restringido
# al tráfico de la app (ver infra/modules/network).

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-db-subnet-group"
  subnet_ids = var.private_subnet_ids

  tags = merge(var.tags, { Name = "${var.name_prefix}-db-subnet-group" })
}

# KMS key dedicada para cifrado at-rest de RDS (TDE). Una key por ambiente
# facilita rotación y revocación independiente entre staging/prod.
resource "aws_kms_key" "rds" {
  description             = "KMS key para cifrado at-rest de RDS — ${var.name_prefix}"
  deletion_window_in_days = 30
  enable_key_rotation     = true

  tags = merge(var.tags, { Name = "${var.name_prefix}-rds-kms" })
}

resource "aws_kms_alias" "rds" {
  name          = "alias/${var.name_prefix}-rds"
  target_key_id = aws_kms_key.rds.key_id
}

resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.name_prefix}-pg16-"
  family      = "postgres16"
  description = "Parámetros Postgres 16 — ${var.name_prefix}"

  # log_connections/log_disconnections ayudan en auditoría e investigación
  # de incidentes (docs/11, Escenario D: tenant data leak).
  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_disconnections"
    value = "1"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = "16"

  instance_class        = var.instance_class
  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.max_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.rds.arn

  db_name  = var.database_name
  username = var.master_username

  # Si no se provee un secreto externo, Terraform/RDS gestiona y rota la
  # contraseña del usuario de migración automáticamente vía Secrets Manager.
  manage_master_user_password = var.master_password_secret_arn == "" ? true : false
  password                    = var.master_password_secret_arn == "" ? null : data.aws_secretsmanager_secret_version.master_password[0].secret_string

  multi_az               = var.multi_az
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.security_group_id]
  publicly_accessible    = false

  backup_retention_period = var.backup_retention_days
  backup_window           = "06:00-07:00" # UTC ~ madrugada America/Mexico_City
  maintenance_window      = "sun:07:30-sun:08:30"
  copy_tags_to_snapshot   = true

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = !var.deletion_protection
  final_snapshot_identifier = var.deletion_protection ? "${var.name_prefix}-final-snapshot" : null

  parameter_group_name = aws_db_parameter_group.this.name

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  auto_minor_version_upgrade = true
  apply_immediately          = false

  tags = merge(var.tags, { Name = "${var.name_prefix}-postgres" })
}

data "aws_secretsmanager_secret_version" "master_password" {
  count     = var.master_password_secret_arn == "" ? 0 : 1
  secret_id = var.master_password_secret_arn
}

# Secreto para la contraseña del rol de aplicación (DB_APP_PASSWORD), el
# rol NOSUPERUSER NOBYPASSRLS que crea la migración Flyway V3. Terraform
# genera un valor aleatorio; el valor real del rol en Postgres se setea
# fuera de Terraform (vía la migración Flyway con placeholders, corrida
# por el pipeline/operador) usando este mismo secreto como fuente.
resource "random_password" "app_user" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "db_app_password" {
  name        = "${var.name_prefix}/db-app-password"
  description = "Password de DB_APP_USER (rol NOSUPERUSER NOBYPASSRLS, migración Flyway V3) — ${var.name_prefix}"
  kms_key_id  = aws_kms_key.rds.id

  tags = var.tags
}

resource "aws_secretsmanager_secret_version" "db_app_password" {
  secret_id     = aws_secretsmanager_secret.db_app_password.id
  secret_string = random_password.app_user.result
}

# Clave de cifrado de columna (APP_PII_KEY) para CURP/RFC/NSS. Se gestiona
# en Secrets Manager y se inyecta en la task como secret (ver infra/modules/app).
resource "random_password" "pii_key" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "app_pii_key" {
  name        = "${var.name_prefix}/app-pii-key"
  description = "APP_PII_KEY — clave de cifrado de columna (CURP/RFC/NSS) — ${var.name_prefix}"
  kms_key_id  = aws_kms_key.rds.id

  tags = var.tags
}

resource "aws_secretsmanager_secret_version" "app_pii_key" {
  secret_id     = aws_secretsmanager_secret.app_pii_key.id
  secret_string = random_password.pii_key.result
}

# ── Alarmas CloudWatch: storage y CPU de RDS ────────────────────────────

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${var.name_prefix}-rds-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "CPU de RDS > 80% por 15 min — ${var.name_prefix}"
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.this.id
  }
  alarm_actions = var.alarm_sns_topic_arn == "" ? [] : [var.alarm_sns_topic_arn]
  tags          = var.tags
}

resource "aws_cloudwatch_metric_alarm" "rds_storage" {
  alarm_name          = "${var.name_prefix}-rds-storage-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  metric_name         = "FreeStorageSpace"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 2 * 1024 * 1024 * 1024 # 2 GiB libres
  alarm_description   = "Espacio libre de RDS < 2GiB — ${var.name_prefix}"
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.this.id
  }
  alarm_actions = var.alarm_sns_topic_arn == "" ? [] : [var.alarm_sns_topic_arn]
  tags          = var.tags
}
