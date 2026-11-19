resource "aws_elasticache_subnet_group" "redis" {
  name       = "${var.app_name}-redis-subnet-group"
  subnet_ids = aws_subnet.database[*].id

  tags = {
    Name = "${var.app_name}-redis-subnet-group"
  }
}

resource "aws_elasticache_parameter_group" "redis7" {
  name   = "${var.app_name}-redis7-params"
  family = "redis7"

  parameter {
    name  = "maxmemory-policy"
    value = "volatile-lru"
  }
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id = "${var.app_name}-redis"
  description          = "Redis cache for PayFlow wallet balances"
  node_type            = "cache.t4g.micro"
  port                 = 6379
  parameter_group_name = aws_elasticache_parameter_group.redis7.name
  subnet_group_name    = aws_elasticache_subnet_group.redis.name
  security_group_ids   = [aws_security_group.redis.id]

  num_cache_clusters         = var.portfolio_mode ? 1 : 2
  automatic_failover_enabled = var.portfolio_mode ? false : true
  multi_az_enabled           = var.portfolio_mode ? false : true

  transit_encryption_enabled = true
  auth_token                 = random_password.redis_auth.result
  apply_immediately          = true

  tags = {
    Name = "${var.app_name}-redis"
  }
}
