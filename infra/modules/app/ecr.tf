# ECR como registro de imágenes (consistente con lo usado por el workflow
# de deploy; el workflow de CI publica a GHCR para builds de PR/main, y un
# paso adicional de "promote" copia la imagen aprobada a ECR antes de
# desplegar — ver docs/17-ci-cd-y-despliegue.md § Registro de imágenes).
resource "aws_ecr_repository" "this" {
  name                 = "assistance-checker"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = var.tags
}

resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Mantener solo las últimas 20 imágenes"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 20
        }
        action = { type = "expire" }
      }
    ]
  })
}
