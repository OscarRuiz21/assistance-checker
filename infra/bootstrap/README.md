# Bootstrap del backend remoto de Terraform

Este directorio es un **ejemplo comentado**, no se ejecuta en CI. Antes de
usar `backend "s3"` en `infra/envs/{staging,prod,demo}`, hay que crear una
vez (manualmente o con este mismo Terraform en modo local) el bucket S3 y
la tabla DynamoDB de lock, **por cuenta AWS** (compartido entre ambientes
está bien si se usa un `key` distinto por ambiente, como ya están los
`versions.tf`).

```hcl
# infra/bootstrap/main.tf — ejecutar UNA VEZ con terraform apply local
# (sin backend remoto, es el huevo-y-la-gallina de siempre).

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region = "us-east-1"
}

resource "aws_s3_bucket" "tfstate" {
  bucket = "assistance-checker-tfstate"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket                  = aws_s3_bucket.tfstate.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_dynamodb_table" "tfstate_lock" {
  name         = "assistance-checker-tfstate-lock"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }
}
```

Tras aplicar esto una vez, descomentar el bloque `backend "s3" { ... }` en
cada `infra/envs/<env>/versions.tf` (usando el mismo bucket, una `key` por
ambiente) y correr `terraform init -migrate-state`.
