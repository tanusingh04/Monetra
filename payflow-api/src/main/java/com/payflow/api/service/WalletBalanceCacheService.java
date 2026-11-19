package com.payflow.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class WalletBalanceCacheService {

    private static final Logger log = LoggerFactory.getLogger(WalletBalanceCacheService.class);
    private static final String BALANCE_KEY_PREFIX = "wallet:balance:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, String> redisTemplate;
    private final com.payflow.api.metrics.PayFlowMetricsService metricsService;

    public WalletBalanceCacheService(RedisTemplate<String, String> redisTemplate,
                                     @org.springframework.beans.factory.annotation.Autowired(required = false)
                                     com.payflow.api.metrics.PayFlowMetricsService metricsService) {
        this.redisTemplate = redisTemplate;
        this.metricsService = metricsService;
    }

    public Optional<BigDecimal> getCachedBalance(UUID walletId) {
        try {
            String key = buildKey(walletId);
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                if (metricsService != null) metricsService.incrementRedisCacheHit();
                return Optional.of(new BigDecimal(value));
            }
            if (metricsService != null) metricsService.incrementRedisCacheMiss();
        } catch (Exception ex) {
            if (metricsService != null) metricsService.incrementRedisCacheMiss();
            log.warn("Redis unavailable for reading balance of wallet {}. Falling back to PostgreSQL. Error: {}", walletId, ex.getMessage());
        }
        return Optional.empty();
    }

    public void cacheBalance(UUID walletId, BigDecimal balance) {
        try {
            String key = buildKey(walletId);
            redisTemplate.opsForValue().set(key, balance.toPlainString(), DEFAULT_TTL);
        } catch (Exception ex) {
            log.warn("Failed to cache balance in Redis for wallet {}: {}", walletId, ex.getMessage());
        }
    }

    public void evictBalance(UUID walletId) {
        try {
            String key = buildKey(walletId);
            redisTemplate.delete(key);
            log.debug("Evicted cached balance for wallet {}", walletId);
        } catch (Exception ex) {
            log.warn("Failed to evict Redis cache for wallet {}: {}", walletId, ex.getMessage());
        }
    }

    private String buildKey(UUID walletId) {
        return BALANCE_KEY_PREFIX + walletId;
    }
}
