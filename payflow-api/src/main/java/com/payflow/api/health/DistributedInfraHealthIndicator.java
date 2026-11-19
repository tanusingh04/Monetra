package com.payflow.api.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HealthIndicator verifying connectivity, responsiveness, and operational health
 * of the three distributed infrastructure backbones: PostgreSQL, Redis, and Apache Kafka.
 */
@Component("distributedInfrastructure")
public class DistributedInfraHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaAdmin kafkaAdmin;

    public DistributedInfraHealthIndicator(@Autowired(required = false) DataSource dataSource,
                                            @Autowired(required = false) RedisTemplate<String, String> redisTemplate,
                                            @Autowired(required = false) KafkaAdmin kafkaAdmin) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean isAllHealthy = true;

        // 1. PostgreSQL Health Check
        long dbStart = System.currentTimeMillis();
        try {
            if (dataSource != null) {
                try (Connection conn = dataSource.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT 1");
                    long latency = System.currentTimeMillis() - dbStart;
                    details.put("postgresql", Map.of(
                            "status", "UP",
                            "latencyMs", latency,
                            "catalog", conn.getCatalog() != null ? conn.getCatalog() : "payflow_db"
                    ));
                }
            } else {
                details.put("postgresql", Map.of("status", "DISABLED"));
            }
        } catch (Exception e) {
            isAllHealthy = false;
            details.put("postgresql", Map.of("status", "DOWN", "error", e.getMessage()));
        }

        // 2. Redis Health Check
        long redisStart = System.currentTimeMillis();
        try {
            if (redisTemplate != null && redisTemplate.getConnectionFactory() != null) {
                try (RedisConnection conn = redisTemplate.getConnectionFactory().getConnection()) {
                    String ping = conn.ping();
                    long latency = System.currentTimeMillis() - redisStart;
                    details.put("redis", Map.of(
                            "status", "PONG".equalsIgnoreCase(ping) ? "UP" : "DEGRADED",
                            "latencyMs", latency
                    ));
                }
            } else {
                details.put("redis", Map.of("status", "DISABLED"));
            }
        } catch (Exception e) {
            isAllHealthy = false;
            details.put("redis", Map.of("status", "DOWN", "error", e.getMessage()));
        }

        // 3. Kafka Health Check
        long kafkaStart = System.currentTimeMillis();
        try {
            if (kafkaAdmin != null) {
                try (AdminClient client = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
                    DescribeClusterResult cluster = client.describeCluster();
                    String clusterId = cluster.clusterId().get(2, TimeUnit.SECONDS);
                    int nodeCount = cluster.nodes().get(2, TimeUnit.SECONDS).size();
                    long latency = System.currentTimeMillis() - kafkaStart;
                    details.put("kafka", Map.of(
                            "status", "UP",
                            "latencyMs", latency,
                            "clusterId", clusterId,
                            "nodes", nodeCount
                    ));
                }
            } else {
                details.put("kafka", Map.of("status", "DISABLED"));
            }
        } catch (Exception e) {
            isAllHealthy = false;
            details.put("kafka", Map.of("status", "DOWN", "error", e.getMessage()));
        }

        if (isAllHealthy) {
            return Health.up().withDetails(details).build();
        } else {
            return Health.down().withDetails(details).build();
        }
    }
}
