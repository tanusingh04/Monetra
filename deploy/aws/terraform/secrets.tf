resource "random_password" "db_password" {
  length           = 24
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "random_password" "redis_auth" {
  length  = 32
  special = false
}

resource "random_password" "jwt_secret" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "app_secrets" {
  name                    = "${var.app_name}/${var.environment}/secrets"
  description             = "Production database credentials, JWT keys, and auth tokens for PayFlow"
  recovery_window_in_days = 0

  tags = {
    Name = "${var.app_name}-secrets"
  }
}

resource "aws_secretsmanager_secret_version" "app_secrets_val" {
  secret_id = aws_secretsmanager_secret.app_secrets.id
  secret_string = jsonencode({
    POSTGRES_USER     = "payflow_admin"
    POSTGRES_PASSWORD = random_password.db_password.result
    REDIS_AUTH_TOKEN  = random_password.redis_auth.result
    JWT_SECRET        = random_password.jwt_secret.result
    KAFKA_JAAS_CONFIG = ""
  })
}
