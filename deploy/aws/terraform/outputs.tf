output "alb_dns_name" {
  description = "Public DNS name of the Application Load Balancer"
  value       = aws_lb.api.dns_name
}

output "api_endpoint" {
  description = "Base API URL"
  value       = "http://${aws_lb.api.dns_name}"
}

output "swagger_url" {
  description = "Swagger UI documentation URL"
  value       = "http://${aws_lb.api.dns_name}/swagger-ui.html"
}

output "ecr_api_repository_url" {
  description = "ECR Repository URL for payflow-api"
  value       = aws_ecr_repository.api.repository_url
}

output "ecr_worker_repository_url" {
  description = "ECR Repository URL for payflow-worker"
  value       = aws_ecr_repository.worker.repository_url
}

output "rds_endpoint" {
  description = "PostgreSQL RDS connection endpoint"
  value       = aws_db_instance.postgres.endpoint
}

output "redis_primary_endpoint" {
  description = "Redis primary endpoint"
  value       = aws_elasticache_replication_group.redis.primary_endpoint_address
}

output "secrets_manager_arn" {
  description = "ARN of the Secrets Manager secret"
  value       = aws_secretsmanager_secret.app_secrets.arn
}

output "cloudwatch_log_group_api" {
  description = "CloudWatch Log Group for payflow-api"
  value       = aws_cloudwatch_log_group.api.name
}

output "cloudwatch_log_group_worker" {
  description = "CloudWatch Log Group for payflow-worker"
  value       = aws_cloudwatch_log_group.worker.name
}
