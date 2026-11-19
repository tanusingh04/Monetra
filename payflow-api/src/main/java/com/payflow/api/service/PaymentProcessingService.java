package com.payflow.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.payment.PaymentResponse;
import com.payflow.api.dto.payment.TransferRequest;
import com.payflow.core.domain.idempotency.IdempotencyRecord;
import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.exception.BusinessException;
import com.payflow.core.exception.InsufficientBalanceException;
import com.payflow.core.exception.ResourceNotFoundException;
import com.payflow.core.exception.UnauthorizedAccessException;
import com.payflow.core.repository.PaymentRepository;
import com.payflow.core.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);

    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final IdempotencyService idempotencyService;
    private final AtomicTransferExecutor atomicTransferExecutor;
    private final ObjectMapper objectMapper;
    private final com.payflow.api.metrics.PayFlowMetricsService metricsService;

    public PaymentProcessingService(WalletRepository walletRepository,
                                  PaymentRepository paymentRepository,
                                  IdempotencyService idempotencyService,
                                  AtomicTransferExecutor atomicTransferExecutor,
                                  ObjectMapper objectMapper,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false)
                                  com.payflow.api.metrics.PayFlowMetricsService metricsService) {
        this.walletRepository = walletRepository;
        this.paymentRepository = paymentRepository;
        this.idempotencyService = idempotencyService;
        this.atomicTransferExecutor = atomicTransferExecutor;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    public PaymentResponse transfer(TransferRequest request, String idempotencyKey, UUID authenticatedUserId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required for transfers");
        }
        if (request.senderWalletId().equals(request.receiverWalletId())) {
            throw new BusinessException("Sender and receiver wallets must be different");
        }

        // 1. Ownership Verification
        Wallet senderVerification = walletRepository.findById(request.senderWalletId())
                .orElseThrow(() -> new ResourceNotFoundException("Sender wallet not found: " + request.senderWalletId()));

        if (!senderVerification.getUser().getId().equals(authenticatedUserId)) {
            throw new UnauthorizedAccessException("You are not authorized to transfer from wallet: " + request.senderWalletId());
        }

        // 2. Compute Payload Hash for Idempotency
        String requestHash = idempotencyService.computePayloadHash(request);

        // 3. Check / Acquire Idempotency Lock
        Optional<IdempotencyRecord> existingRecord = idempotencyService.lockOrCreateKey(idempotencyKey, requestHash);
        if (existingRecord.isPresent()) {
            IdempotencyRecord record = existingRecord.get();
            if (record.getStatus() == com.payflow.core.domain.idempotency.IdempotencyStatus.COMPLETED) {
                log.info("Idempotent request recognized for key: {}. Returning cached response.", idempotencyKey);
                try {
                    return objectMapper.readValue(record.getResponseBody(), PaymentResponse.class);
                } catch (JsonProcessingException e) {
                    log.error("Failed to deserialize cached idempotency response", e);
                    throw new IllegalStateException("Failed to parse cached response for idempotency key: " + idempotencyKey);
                }
            } else if (record.getStatus() == com.payflow.core.domain.idempotency.IdempotencyStatus.FAILED) {
                // AUD-04: Replay stored failure response rather than attempting JSON deserialization
                log.warn("Idempotent request recognized for previously failed key: {}", idempotencyKey);
                if (record.getResponseCode() != null && record.getResponseCode() == 422) {
                    throw new InsufficientBalanceException(record.getResponseBody());
                } else {
                    throw new BusinessException(record.getResponseBody());
                }
            }
        }

        // 4. Execute Transfer Atomically via AtomicTransferExecutor
        if (metricsService != null) {
            metricsService.incrementPaymentRequests();
        }
        long startTime = System.nanoTime();
        PaymentResponse response;
        try {
            response = atomicTransferExecutor.executeTransferInTransaction(request, idempotencyKey);
            org.slf4j.MDC.put("paymentId", response.paymentId().toString());
            idempotencyService.markCompleted(idempotencyKey, 200, response, response.paymentId());
            if (metricsService != null) {
                metricsService.incrementSuccessfulPayments();
                metricsService.recordPaymentLatency(() -> {});
            }
            return response;
        } catch (InsufficientBalanceException ex) {
            if (metricsService != null) {
                metricsService.incrementInsufficientBalance();
                metricsService.incrementFailedPayments();
            }
            idempotencyService.markFailed(idempotencyKey, 422, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            if (metricsService != null) {
                metricsService.incrementFailedPayments();
            }
            idempotencyService.markFailed(idempotencyKey, 500, ex.getMessage());
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID paymentId, UUID authenticatedUserId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));

        boolean isSenderOwner = payment.getSenderWallet().getUser().getId().equals(authenticatedUserId);
        boolean isReceiverOwner = payment.getReceiverWallet().getUser().getId().equals(authenticatedUserId);

        if (!isSenderOwner && !isReceiverOwner) {
            throw new UnauthorizedAccessException("You are not authorized to view payment: " + paymentId);
        }

        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> getPaymentHistory(UUID authenticatedUserId, Pageable pageable) {
        Wallet userWallet = walletRepository.findByUserId(authenticatedUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user: " + authenticatedUserId));

        Page<Payment> payments = paymentRepository.findByWalletId(userWallet.getId(), pageable);
        return payments.map(PaymentResponse::from);
    }
}
