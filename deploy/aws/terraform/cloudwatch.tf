resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${var.app_name}-api"
  retention_in_days = var.portfolio_mode ? 7 : 30

  tags = {
    Application = "${var.app_name}-api"
  }
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/ecs/${var.app_name}-worker"
  retention_in_days = var.portfolio_mode ? 7 : 30

  tags = {
    Application = "${var.app_name}-worker"
  }
}
