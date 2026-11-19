# ALB Security Group
resource "aws_security_group" "alb" {
  name        = "${var.app_name}-alb-sg"
  description = "Allows incoming HTTP/HTTPS traffic to Application Load Balancer"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTP Public Access"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS Public Access"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Egress to ECS target group"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.app_name}-alb-sg"
  }
}

# ECS API Security Group
resource "aws_security_group" "ecs_api" {
  name        = "${var.app_name}-ecs-api-sg"
  description = "Allows inbound traffic only from the ALB to port 8080"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Traffic from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "Outbound to RDS, Redis, Kafka, and AWS APIs"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.app_name}-ecs-api-sg"
  }
}

# ECS Worker Security Group
resource "aws_security_group" "ecs_worker" {
  name        = "${var.app_name}-ecs-worker-sg"
  description = "Internal worker security group with no inbound internet exposure"
  vpc_id      = aws_vpc.main.id

  egress {
    description = "Outbound to RDS, Redis, Kafka, and AWS APIs"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.app_name}-ecs-worker-sg"
  }
}

# RDS PostgreSQL Security Group
resource "aws_security_group" "rds" {
  name        = "${var.app_name}-rds-sg"
  description = "Allows PostgreSQL traffic from ECS API and Worker containers only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "PostgreSQL from API tasks"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_api.id, aws_security_group.ecs_worker.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.app_name}-rds-sg"
  }
}

# Redis ElastiCache Security Group
resource "aws_security_group" "redis" {
  name        = "${var.app_name}-redis-sg"
  description = "Allows Redis access strictly from ECS API and Worker containers"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Redis port from API tasks"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_api.id, aws_security_group.ecs_worker.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.app_name}-redis-sg"
  }
}
