variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "acm_certificate_arn" {
  type = string
}

variable "container_image" {
  type = string
}

variable "cors_origins" {
  type    = string
  default = "https://demo.assistance-checker.example.com"
}
