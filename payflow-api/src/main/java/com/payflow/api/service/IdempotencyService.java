package com.payflow.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.core.domain.idempotency.IdempotencyRecord;
import com.payflow.core.domain.idempotency.IdempotencyStatus;
import com.payflow.core.exception.ConcurrentRequestException;
import com.payflow.core.exception.IdempotencyConflictException;
import com.payflow.core.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration IN_PROGRESS_LEASE_DURATION = Duration.ofMinutes(2);

    private final IdempotencyKeyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final com.payflow.api.metrics.PayFlowMetricsService metricsService;

    public IdempotencyService(IdempotencyKeyRepository idempotencyRepository,
                              ObjectMapper objectMapper,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              com.payflow.api.metrics.PayFlowMetricsService metricsService) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    public String computePayloadHash(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize payload for idempotency hashing", e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> lockOrCreateKey(String key, String requestHash) {
        Optional<IdempotencyRecord> existingOpt = idempotencyRepository.findByKey(key);

        if (existingOpt.isPresent()) {
            IdempotencyRecord existing = existingOpt.get();

            if (!existing.getRequestHash().equals(requestHash)) {
                if (metricsService != null) metricsService.incrementIdempotencyConflicts();
                throw new IdempotencyConflictException(
                        "Idempotency key '" + key + "' has already been used with a different request payload"
                );
            }

            if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                // AUD-07: In-Progress Lease Timeout Recovery on Node Crash
                Instant leaseExpiry = existing.getUpdatedAt().plus(IN_PROGRESS_LEASE_DURATION);
                if (Instant.now().isAfter(leaseExpiry)) {
                    log.warn("Idempotency key '{}' in-progress lease expired (created: {}). Node crash suspected. Reclaiming lock.",
                            key, existing.getCreatedAt());
                    existing.setUpdatedAt(Instant.now());
                    idempotencyRepository.save(existing);
                    return Optional.empty(); // Allow re-execution
                }

                if (metricsService != null) metricsService.incrementIdempotencyConflicts();
                throw new ConcurrentRequestException(
                        "A request with idempotency key '" + key + "' is currently in progress. Please retry with exponential backoff."
                );
            }

            return Optional.of(existing);
        }

        IdempotencyRecord newRecord = new IdempotencyRecord(key, requestHash);
        try {
            idempotencyRepository.saveAndFlush(newRecord);
            return Optional.empty();
        } catch (DataIntegrityViolationException e) {
            IdempotencyRecord concurrent = idempotencyRepository.findByKey(key)
                    .orElseThrow(() -> new ConcurrentRequestException("Concurrent request execution detected for key: " + key));

            if (!concurrent.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("Idempotency key '" + key + "' was registered with different payload");
            }
            if (concurrent.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                Instant leaseExpiry = concurrent.getUpdatedAt().plus(IN_PROGRESS_LEASE_DURATION);
                if (Instant.now().isAfter(leaseExpiry)) {
                    concurrent.setUpdatedAt(Instant.now());
                    idempotencyRepository.save(concurrent);
                    return Optional.empty();
                }
                throw new ConcurrentRequestException("Concurrent request in progress for key: " + key);
            }
            return Optional.of(concurrent);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String key, int responseCode, Object responseBody, UUID paymentId) {
        try {
            String bodyString = objectMapper.writeValueAsString(responseBody);
            idempotencyRepository.findByKey(key).ifPresent(record -> {
                record.markCompleted(responseCode, bodyString, paymentId);
                idempotencyRepository.save(record);
            });
        } catch (Exception e) {
            log.error("Failed to mark idempotency key as COMPLETED: {}", key, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String key, int responseCode, String errorMessage) {
        idempotencyRepository.findByKey(key).ifPresent(record -> {
            record.markFailed(responseCode, errorMessage);
            idempotencyRepository.save(record);
        });
    }
}
