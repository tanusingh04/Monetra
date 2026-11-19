resource "aws_db_subnet_group" "rds" {
  name       = "${var.app_name}-rds-subnet-group"
  subnet_ids = aws_subnet.database[*].id

  tags = {
    Name = "${var.app_name}-rds-subnet-group"
  }
}

resource "aws_db_parameter_group" "pg16" {
  name   = "${var.app_name}-pg16-params"
  family = "postgres16"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_disconnections"
    value = "1"
  }
}

resource "aws_db_instance" "postgres" {
  identifier        = "${var.app_name}-postgres"
  engine            = "postgres"
  engine_version    = "16.2"
  instance_class    = var.portfolio_mode ? "db.t4g.micro" : "db.m6g.large"
  allocated_storage = 20
  max_allocated_storage = 100
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "payflow_db"
  username = "payflow_admin"
  password = random_password.db_password.result

  db_subnet_group_name   = aws_db_subnet_group.rds.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.pg16.name

  multi_az                = var.portfolio_mode ? false : true
  publicly_accessible     = false
  skip_final_snapshot     = var.portfolio_mode ? true : false
  final_snapshot_identifier = "${var.app_name}-postgres-final-snapshot"
  backup_retention_period = var.portfolio_mode ? 1 : 7
  deletion_protection     = var.portfolio_mode ? false : true

  tags = {
    Name = "${var.app_name}-postgres"
  }
}
