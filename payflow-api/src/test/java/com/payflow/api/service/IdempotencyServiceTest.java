package com.payflow.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.core.domain.idempotency.IdempotencyRecord;
import com.payflow.core.domain.idempotency.IdempotencyStatus;
import com.payflow.core.exception.ConcurrentRequestException;
import com.payflow.core.exception.IdempotencyConflictException;
import com.payflow.core.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyKeyRepository idempotencyRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private IdempotencyService idempotencyService;

    @Test
    @DisplayName("Should create lock for new idempotency key")
    void lockOrCreateKey_NewKey_ReturnsEmpty() {
        String key = "test-key-1";
        String hash = "hash-1";

        when(idempotencyRepository.findByKey(key)).thenReturn(Optional.empty());

        Optional<IdempotencyRecord> result = idempotencyService.lockOrCreateKey(key, hash);

        assertThat(result).isEmpty();
        verify(idempotencyRepository).saveAndFlush(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("Should throw IdempotencyConflictException when same key used with different payload")
    void lockOrCreateKey_DifferentPayload_ThrowsConflict() {
        String key = "test-key-2";
        IdempotencyRecord existing = new IdempotencyRecord(key, "original-hash");
        when(idempotencyRepository.findByKey(key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> idempotencyService.lockOrCreateKey(key, "different-hash"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request payload");
    }

    @Test
    @DisplayName("Should throw ConcurrentRequestException when another request is actively processing the key")
    void lockOrCreateKey_InProgress_ThrowsConcurrentException() {
        String key = "test-key-3";
        IdempotencyRecord existing = new IdempotencyRecord(key, "same-hash");
        existing.setStatus(IdempotencyStatus.IN_PROGRESS);
        when(idempotencyRepository.findByKey(key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> idempotencyService.lockOrCreateKey(key, "same-hash"))
                .isInstanceOf(ConcurrentRequestException.class)
                .hasMessageContaining("currently in progress");
    }

    @Test
    @DisplayName("Should return existing record when key is already COMPLETED")
    void lockOrCreateKey_Completed_ReturnsRecord() {
        String key = "test-key-4";
        IdempotencyRecord existing = new IdempotencyRecord(key, "same-hash");
        existing.setStatus(IdempotencyStatus.COMPLETED);
        when(idempotencyRepository.findByKey(key)).thenReturn(Optional.of(existing));

        Optional<IdempotencyRecord> result = idempotencyService.lockOrCreateKey(key, "same-hash");

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    @DisplayName("AUD-07: Should reclaim lock when IN_PROGRESS key lease has expired (> 2 minutes old)")
    void lockOrCreateKey_InProgress_LeaseExpired_ReclaimsLock() {
        String key = "test-key-expired";
        IdempotencyRecord existing = new IdempotencyRecord(key, "same-hash");
        existing.setStatus(IdempotencyStatus.IN_PROGRESS);
        // Simulate record updated 5 minutes ago
        org.springframework.test.util.ReflectionTestUtils.setField(
                existing, "updatedAt", java.time.Instant.now().minus(java.time.Duration.ofMinutes(5))
        );
        when(idempotencyRepository.findByKey(key)).thenReturn(Optional.of(existing));

        Optional<IdempotencyRecord> result = idempotencyService.lockOrCreateKey(key, "same-hash");

        assertThat(result).isEmpty(); // Lock successfully reclaimed for re-execution
        verify(idempotencyRepository).save(existing);
    }
}
