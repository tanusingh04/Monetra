variable "aws_region" {
  description = "AWS deployment region"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment (e.g. dev, staging, prod)"
  type        = string
  default     = "prod"
}

variable "app_name" {
  description = "Application name"
  type        = string
  default     = "payflow"
}

variable "portfolio_mode" {
  description = "Cost-conscious portfolio mode (uses t4g.micro, single-AZ, and eliminates expensive NAT Gateways to minimize AWS bill)"
  type        = bool
  default     = true
}

variable "vpc_cidr" {
  description = "VPC CIDR block"
  type        = string
  default     = "10.0.0.0/16"
}

variable "api_cpu" {
  description = "vCPU units for payflow-api (256 = 0.25 vCPU, 512 = 0.5 vCPU)"
  type        = string
  default     = "512"
}

variable "api_memory" {
  description = "RAM in MB for payflow-api (512, 1024, 2048)"
  type        = string
  default     = "1024"
}

variable "worker_cpu" {
  description = "vCPU units for payflow-worker"
  type        = string
  default     = "256"
}

variable "worker_memory" {
  description = "RAM in MB for payflow-worker"
  type        = string
  default     = "512"
}

variable "api_desired_count" {
  description = "Number of API task replicas"
  type        = number
  default     = 1
}

variable "worker_desired_count" {
  description = "Number of Worker task replicas"
  type        = number
  default     = 1
}
