package com.payflow.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletBalanceCacheServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private WalletBalanceCacheService walletBalanceCacheService;

    @Test
    @DisplayName("Should return balance from cache on cache hit")
    void getCachedBalance_Hit() {
        UUID walletId = UUID.randomUUID();
        String key = "wallet:balance:" + walletId;

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn("4500.0000");

        Optional<BigDecimal> result = walletBalanceCacheService.getCachedBalance(walletId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo(BigDecimal.valueOf(4500));
    }

    @Test
    @DisplayName("Should return empty optional on cache miss")
    void getCachedBalance_Miss() {
        UUID walletId = UUID.randomUUID();
        String key = "wallet:balance:" + walletId;

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(null);

        Optional<BigDecimal> result = walletBalanceCacheService.getCachedBalance(walletId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should gracefully handle Redis failure without throwing exception")
    void getCachedBalance_RedisFailure_FallbackGraceful() {
        UUID walletId = UUID.randomUUID();

        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Redis connection refused"));

        Optional<BigDecimal> result = walletBalanceCacheService.getCachedBalance(walletId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should successfully cache balance with TTL")
    void cacheBalance_Success() {
        UUID walletId = UUID.randomUUID();
        String key = "wallet:balance:" + walletId;

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        walletBalanceCacheService.cacheBalance(walletId, BigDecimal.valueOf(3200));

        verify(valueOperations).set(eq(key), eq("3200"), any(Duration.class));
    }

    @Test
    @DisplayName("Should delete cache key on eviction")
    void evictBalance_Success() {
        UUID walletId = UUID.randomUUID();
        String key = "wallet:balance:" + walletId;

        walletBalanceCacheService.evictBalance(walletId);

        verify(redisTemplate).delete(key);
    }
}
