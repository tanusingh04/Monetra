# ECR Container Registries
resource "aws_ecr_repository" "api" {
  name                 = "${var.app_name}-api"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_repository" "worker" {
  name                 = "${var.app_name}-worker"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

# ECS Cluster
resource "aws_ecs_cluster" "main" {
  name = "${var.app_name}-cluster"

  setting {
    name  = "containerInsights"
    value = var.portfolio_mode ? "disabled" : "enabled"
  }
}

# IAM Execution Role (For pulling images, writing logs, resolving secrets)
resource "aws_iam_role" "ecs_execution" {
  name = "${var.app_name}-ecs-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_execution_standard" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_policy" "ecs_secrets_policy" {
  name        = "${var.app_name}-ecs-secrets-policy"
  description = "Allows ECS tasks to read secrets from AWS Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "kms:Decrypt"
        ]
        Resource = [
          aws_secretsmanager_secret.app_secrets.arn
        ]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_secrets_attach" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = aws_iam_policy.ecs_secrets_policy.arn
}

# IAM Task Role (For application runtime permissions)
resource "aws_iam_role" "ecs_task" {
  name = "${var.app_name}-ecs-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })
}

# ECS Task Definition: API
resource "aws_ecs_task_definition" "api" {
  family                   = "${var.app_name}-api"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name      = "payflow-api"
      image     = "${aws_ecr_repository.api.repository_url}:latest"
      essential = true
      portMappings = [
        {
          containerPort = 8080
          hostPort      = 8080
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.endpoint}/${aws_db_instance.postgres.db_name}?sslmode=require" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_replication_group.redis.primary_endpoint_address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_SSL", value = "true" },
        { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = "localhost:9092" },
        { name = "AWS_REGION", value = var.aws_region }
      ]
      secrets = [
        { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${aws_secretsmanager_secret.app_secrets.arn}:POSTGRES_USER::" },
        { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app_secrets.arn}:POSTGRES_PASSWORD::" },
        { name = "SPRING_DATA_REDIS_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app_secrets.arn}:REDIS_AUTH_TOKEN::" },
        { name = "APP_JWT_SECRET", valueFrom = "${aws_secretsmanager_secret.app_secrets.arn}:JWT_SECRET::" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.api.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }
      healthCheck = {
        command     = ["CMD-SHELL", "wget -q --spider http://localhost:8080/actuator/health || exit 1"]
        interval    = 20
        timeout     = 5
        retries     = 3
        startPeriod = 45
      }
    }
  ])
}

# ECS Task Definition: Worker
resource "aws_ecs_task_definition" "worker" {
  family                   = "${var.app_name}-worker"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.worker_cpu
  memory                   = var.worker_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name      = "payflow-worker"
      image     = "${aws_ecr_repository.worker.repository_url}:latest"
      essential = true
      portMappings = [
        {
          containerPort = 8081
          hostPort      = 8081
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.endpoint}/${aws_db_instance.postgres.db_name}?sslmode=require" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_replication_group.redis.primary_endpoint_address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_SSL", value = "true" },
        { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = "localhost:9092" },
        { name = "AWS_REGION", value = var.aws_region }
      ]
      secrets = [
        { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${aws_secretsmanager_secret.app_secrets.arn}:POSTGRES_USER::" },
        { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app_secrets.arn}:POSTGRES_PASSWORD::" },
        { name = "SPRING_DATA_REDIS_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app_secrets.arn}:REDIS_AUTH_TOKEN::" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.worker.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }
      healthCheck = {
        command     = ["CMD-SHELL", "wget -q --spider http://localhost:8081/actuator/health || exit 1"]
        interval    = 20
        timeout     = 5
        retries     = 3
        startPeriod = 45
      }
    }
  ])
}

# ECS Service: API
resource "aws_ecs_service" "api" {
  name            = "${var.app_name}-api-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.portfolio_mode ? aws_subnet.public[*].id : aws_subnet.private[*].id
    security_groups  = [aws_security_group.ecs_api.id]
    assign_public_ip = var.portfolio_mode ? true : false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "payflow-api"
    container_port   = 8080
  }

  deployment_controller {
    type = "ECS"
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [aws_lb_listener.http]
}

# ECS Service: Worker
resource "aws_ecs_service" "worker" {
  name            = "${var.app_name}-worker-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.worker_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.portfolio_mode ? aws_subnet.public[*].id : aws_subnet.private[*].id
    security_groups  = [aws_security_group.ecs_worker.id]
    assign_public_ip = var.portfolio_mode ? true : false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
}
