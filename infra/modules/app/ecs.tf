resource "aws_ecs_cluster" "this" {
  name = "${var.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = var.tags
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.name_prefix}-app"
  retention_in_days = var.log_retention_days

  tags = var.tags
}

# ── IAM: execution role (pull de imagen, logs, secrets) ─────────────────
data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name_prefix}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Permiso explícito para leer los secretos inyectados como env vars de la
# task (DB_APP_PASSWORD, DB_MIGRATION_PASSWORD, APP_PII_KEY).
data "aws_iam_policy_document" "execution_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      var.db_app_password_secret_arn,
      var.db_migration_password_secret_arn,
      var.app_pii_key_secret_arn,
    ]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "${var.name_prefix}-ecs-execution-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

# ── IAM: task role (permisos del código de la app en runtime) ───────────
# MVP no necesita llamar a otros servicios AWS desde dentro de la app
# (S3/SES llegan en fases posteriores, ver docs/16). Rol vacío por ahora,
# listo para adjuntar policies futuras sin tocar la task definition.
resource "aws_iam_role" "task" {
  name               = "${var.name_prefix}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
  tags               = var.tags
}

# ── Task definition ──────────────────────────────────────────────────────
resource "aws_ecs_task_definition" "app" {
  family                   = "${var.name_prefix}-app"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = "assistance-backend"
      image     = var.container_image
      essential = true
      portMappings = [
        { containerPort = var.container_port, protocol = "tcp" },
        { containerPort = var.management_port, protocol = "tcp" },
      ]
      environment = concat([
        { name = "SPRING_PROFILES_ACTIVE", value = var.spring_profile },
        { name = "SERVER_PORT", value = tostring(var.container_port) },
        { name = "MANAGEMENT_PORT", value = tostring(var.management_port) },
        { name = "DB_URL", value = var.db_url },
        { name = "DB_MIGRATION_USER", value = var.db_migration_user },
        { name = "DB_APP_USER", value = var.db_app_user },
        { name = "CORS_ORIGINS", value = var.cors_origins },
        ],
        var.auth_issuer_uri == "" ? [] : [
          { name = "AUTH_ISSUER_URI", value = var.auth_issuer_uri }
        ]
      )
      secrets = [
        {
          name      = "DB_MIGRATION_PASSWORD"
          valueFrom = var.db_migration_password_is_json_kv ? "${var.db_migration_password_secret_arn}:password::" : var.db_migration_password_secret_arn
        },
        { name = "DB_APP_PASSWORD", valueFrom = var.db_app_password_secret_arn },
        { name = "APP_PII_KEY", valueFrom = var.app_pii_key_secret_arn },
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app.name
          "awslogs-region"        = data.aws_region.current.name
          "awslogs-stream-prefix" = "app"
        }
      }
      healthCheck = {
        command     = ["CMD-SHELL", "curl -f http://localhost:${var.management_port}/actuator/health || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }
    }
  ])

  tags = var.tags
}

data "aws_region" "current" {}

resource "aws_ecs_service" "app" {
  name            = "${var.name_prefix}-app"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = var.private_subnet_ids
    security_groups = [var.app_security_group_id]
    # Sin IP pública: las tasks viven en subnets privadas, salen a internet
    # vía NAT Gateway (ver infra/modules/network).
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "assistance-backend"
    container_port   = var.container_port
  }

  deployment_circuit_breaker {
    enable   = var.enable_deployment_circuit_breaker
    rollback = var.enable_deployment_circuit_breaker
  }

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  # Riesgo documentado: con minimum_healthy=100% y maximum=200%, durante un
  # deploy puede haber hasta 2x tasks arrancando a la vez; Flyway serializa
  # con su lock de BD (ver docs/17-ci-cd-y-despliegue.md § Migraciones).

  depends_on = [aws_lb_listener.https]

  tags = var.tags

  lifecycle {
    ignore_changes = [task_definition] # CI actualiza vía render-task-definition + deploy action
  }
}

# ── Alarmas CloudWatch: CPU / memoria del servicio ECS ──────────────────
resource "aws_cloudwatch_metric_alarm" "ecs_cpu" {
  alarm_name          = "${var.name_prefix}-ecs-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/ECS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "CPU promedio del servicio ECS > 80% por 15 min — ${var.name_prefix}"
  dimensions = {
    ClusterName = aws_ecs_cluster.this.name
    ServiceName = aws_ecs_service.app.name
  }
  alarm_actions = var.alarm_sns_topic_arn == "" ? [] : [var.alarm_sns_topic_arn]
  tags          = var.tags
}

resource "aws_cloudwatch_metric_alarm" "ecs_memory" {
  alarm_name          = "${var.name_prefix}-ecs-memory-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "MemoryUtilization"
  namespace           = "AWS/ECS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "Memoria promedio del servicio ECS > 80% por 15 min — ${var.name_prefix}"
  dimensions = {
    ClusterName = aws_ecs_cluster.this.name
    ServiceName = aws_ecs_service.app.name
  }
  alarm_actions = var.alarm_sns_topic_arn == "" ? [] : [var.alarm_sns_topic_arn]
  tags          = var.tags
}
