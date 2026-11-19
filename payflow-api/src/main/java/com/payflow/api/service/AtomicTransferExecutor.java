package com.payflow.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.payment.PaymentResponse;
import com.payflow.api.dto.payment.TransferRequest;
import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.domain.wallet.WalletStatus;
import com.payflow.core.event.PaymentCompletedEvent;
import com.payflow.core.exception.BusinessException;
import com.payflow.core.exception.ResourceNotFoundException;
import com.payflow.core.repository.OutboxEventRepository;
import com.payflow.core.repository.PaymentRepository;
import com.payflow.core.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Service
public class AtomicTransferExecutor {

    private static final Logger log = LoggerFactory.getLogger(AtomicTransferExecutor.class);

    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final OutboxEventRepository outboxEventRepository;
    private final WalletBalanceCacheService walletBalanceCacheService;
    private final ObjectMapper objectMapper;

    public AtomicTransferExecutor(WalletRepository walletRepository,
                                  PaymentRepository paymentRepository,
                                  LedgerService ledgerService,
                                  OutboxEventRepository outboxEventRepository,
                                  WalletBalanceCacheService walletBalanceCacheService,
                                  ObjectMapper objectMapper) {
        this.walletRepository = walletRepository;
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.outboxEventRepository = outboxEventRepository;
        this.walletBalanceCacheService = walletBalanceCacheService;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentResponse executeTransferInTransaction(TransferRequest request, String idempotencyKey) {
        UUID senderId = request.senderWalletId();
        UUID receiverId = request.receiverWalletId();

        if (senderId.equals(receiverId)) {
            throw new BusinessException("Sender and receiver wallets must be different");
        }

        BigDecimal transferAmount = request.amount().setScale(4, RoundingMode.HALF_UP);

        // 1. Deterministic Lock Ordering: min(senderId, receiverId) first, then max(senderId, receiverId)
        // Prevents database deadlocks when concurrent transfers occur between the same wallets in reverse directions
        UUID firstLockId = senderId.compareTo(receiverId) < 0 ? senderId : receiverId;
        UUID secondLockId = senderId.compareTo(receiverId) < 0 ? receiverId : senderId;

        Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + firstLockId));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + secondLockId));

        Wallet senderWallet = firstWallet.getId().equals(senderId) ? firstWallet : secondWallet;
        Wallet receiverWallet = firstWallet.getId().equals(receiverId) ? firstWallet : secondWallet;

        // 2. Validate Wallet Statuses
        if (senderWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BusinessException("Sender wallet is not active: " + senderWallet.getStatus());
        }
        if (receiverWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BusinessException("Receiver wallet is not active: " + receiverWallet.getStatus());
        }

        // 3. Strict Currency Parity Verification (AUD-03)
        if (!senderWallet.getCurrency().equalsIgnoreCase(receiverWallet.getCurrency())) {
            throw new BusinessException("Cross-currency transfers are not supported. Sender currency: "
                    + senderWallet.getCurrency() + ", Receiver currency: " + receiverWallet.getCurrency());
        }
        if (request.currency() != null && !request.currency().isBlank()
                && !request.currency().equalsIgnoreCase(senderWallet.getCurrency())) {
            throw new BusinessException("Requested currency (" + request.currency()
                    + ") does not match sender wallet currency (" + senderWallet.getCurrency() + ")");
        }

        // 4. Atomically Debit Sender and Credit Receiver (enforces non-negative balance)
        senderWallet.debit(transferAmount);
        receiverWallet.credit(transferAmount);

        walletRepository.save(senderWallet);
        walletRepository.save(receiverWallet);

        // 5. Create and Save Completed Payment Record
        Payment payment = new Payment(
                idempotencyKey,
                senderWallet,
                receiverWallet,
                transferAmount,
                senderWallet.getCurrency()
        );
        payment.markCompleted();
        payment = paymentRepository.save(payment);

        // 6. Append-Only Double-Entry Ledger Entries (1 Debit, 1 Credit)
        ledgerService.recordDoubleEntry(payment, senderWallet, receiverWallet, transferAmount);

        // 7. Record Transactional Outbox Event (guarantees at-least-once Kafka publication)
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                UUID.randomUUID(),
                payment.getId(),
                payment.getIdempotencyKey(),
                senderWallet.getId(),
                receiverWallet.getId(),
                transferAmount,
                payment.getCurrency(),
                Instant.now()
        );

        try {
            String eventPayload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = new OutboxEvent(
                    "PAYMENT",
                    payment.getId().toString(),
                    "PAYMENT_COMPLETED",
                    eventPayload
            );
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize PaymentCompletedEvent to JSON", e);
            throw new IllegalStateException("Outbox serialization failure", e);
        }

        // 8. Post-Commit Cache Invalidation (AUD-02)
        // Defers eviction until PostgreSQL transaction commits to prevent stale read-aside races
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    walletBalanceCacheService.evictBalance(senderId);
                    walletBalanceCacheService.evictBalance(receiverId);
                    log.debug("Post-commit cache eviction completed for wallets {} and {}", senderId, receiverId);
                }
            });
        } else {
            walletBalanceCacheService.evictBalance(senderId);
            walletBalanceCacheService.evictBalance(receiverId);
        }

        log.info("Successfully executed atomic transfer: paymentId={}, amount={} {}, senderWallet={}, receiverWallet={}",
                payment.getId(), transferAmount, payment.getCurrency(), senderId, receiverId);

        return PaymentResponse.from(payment);
    }
}
