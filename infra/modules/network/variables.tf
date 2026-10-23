variable "name_prefix" {
  description = "Prefijo para nombrar los recursos (ej. 'assistance-staging')."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block de la VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "azs" {
  description = "Availability zones a usar (mínimo 2 para Multi-AZ)."
  type        = list(string)
}

variable "public_subnet_cidrs" {
  description = "CIDRs de subnets públicas, una por AZ (ALB, NAT gateway)."
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "CIDRs de subnets privadas, una por AZ (ECS tasks, RDS)."
  type        = list(string)
}

variable "single_nat_gateway" {
  description = "Si es true, usa un solo NAT Gateway para todas las AZ (ahorro de costo, menor HA). Recomendado false en prod."
  type        = bool
  default     = true
}

variable "tags" {
  description = "Tags comunes a todos los recursos."
  type        = map(string)
  default     = {}
}
