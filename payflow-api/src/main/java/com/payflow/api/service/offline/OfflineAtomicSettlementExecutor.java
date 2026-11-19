package com.payflow.api.service.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.offline.OfflineReceiptDto;
import com.payflow.api.service.WalletBalanceCacheService;
import com.payflow.core.domain.ledger.EntryType;
import com.payflow.core.domain.ledger.LedgerEntry;
import com.payflow.core.domain.offline.FraudProof;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.offline.OfflineProtectedSettlement;
import com.payflow.core.domain.offline.OfflineTransaction;
import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.domain.wallet.WalletStatus;
import com.payflow.core.event.OfflineReceiptProtectedEvent;
import com.payflow.core.event.PaymentCompletedEvent;
import com.payflow.core.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Reusable component executing atomic financial settlement for offline receipts.
 * Enforces canonical wallet lock ordering, balance adjustments, payment creation,
 * immutable double-entry ledger entries, allowance tracking, outbox event generation,
 * and post-commit cache eviction across all settlement modes:
 * STANDARD, PROTECTED_BY_RESERVE, and GRACE_SETTLEMENT.
 */
@Component
public class OfflineAtomicSettlementExecutor {

    private static final Logger log = LoggerFactory.getLogger(OfflineAtomicSettlementExecutor.class);

    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OfflineAllowanceRepository allowanceRepository;
    private final OfflineTransactionRepository offlineTransactionRepository;
    private final OfflineProtectedSettlementRepository protectedSettlementRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final OfflineCryptoService cryptoService;
    private final WalletBalanceCacheService walletBalanceCacheService;
    private final ObjectMapper objectMapper;
    private final ReputationService reputationService;

    @org.springframework.beans.factory.annotation.Autowired
    public OfflineAtomicSettlementExecutor(WalletRepository walletRepository,
                                          PaymentRepository paymentRepository,
                                          LedgerEntryRepository ledgerEntryRepository,
                                          OfflineAllowanceRepository allowanceRepository,
                                          OfflineTransactionRepository offlineTransactionRepository,
                                          OfflineProtectedSettlementRepository protectedSettlementRepository,
                                          OutboxEventRepository outboxEventRepository,
                                          OfflineCryptoService cryptoService,
                                          WalletBalanceCacheService walletBalanceCacheService,
                                          ObjectMapper objectMapper,
                                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                                          ReputationService reputationService) {
        this.walletRepository = walletRepository;
        this.paymentRepository = paymentRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.allowanceRepository = allowanceRepository;
        this.offlineTransactionRepository = offlineTransactionRepository;
        this.protectedSettlementRepository = protectedSettlementRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.cryptoService = cryptoService;
        this.walletBalanceCacheService = walletBalanceCacheService;
        this.objectMapper = objectMapper;
        this.reputationService = reputationService;
    }

    public OfflineAtomicSettlementExecutor(WalletRepository walletRepository,
                                          PaymentRepository paymentRepository,
                                          LedgerEntryRepository ledgerEntryRepository,
                                          OfflineAllowanceRepository allowanceRepository,
                                          OfflineTransactionRepository offlineTransactionRepository,
                                          OfflineProtectedSettlementRepository protectedSettlementRepository,
                                          OutboxEventRepository outboxEventRepository,
                                          OfflineCryptoService cryptoService,
                                          WalletBalanceCacheService walletBalanceCacheService,
                                          ObjectMapper objectMapper) {
        this(walletRepository, paymentRepository, ledgerEntryRepository, allowanceRepository,
                offlineTransactionRepository, protectedSettlementRepository, outboxEventRepository,
                cryptoService, walletBalanceCacheService, objectMapper, null);
    }

    @Transactional
    public Payment performAtomicSettlement(OfflineReceiptDto receipt,
                                           OfflineAllowance allowance,
                                           SettlementMode mode,
                                           FraudProof fraudProof) {
        // 1. Canonical Lock Ordering on Wallets to prevent deadlocks:
        // Lock allowance row -> wallet rows in UUID order
        UUID firstLockId = receipt.senderWalletId().compareTo(receipt.receiverWalletId()) < 0
                ? receipt.senderWalletId() : receipt.receiverWalletId();
        UUID secondLockId = receipt.senderWalletId().compareTo(receipt.receiverWalletId()) < 0
                ? receipt.receiverWalletId() : receipt.senderWalletId();

        Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + firstLockId));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + secondLockId));

        Wallet senderWallet = receipt.senderWalletId().equals(firstWallet.getId()) ? firstWallet : secondWallet;
        Wallet receiverWallet = receipt.receiverWalletId().equals(firstWallet.getId()) ? firstWallet : secondWallet;

        if (receiverWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Receiver wallet is not active: " + receiverWallet.getStatus());
        }

        if (!senderWallet.getCurrency().equalsIgnoreCase(receipt.currency()) ||
            !receiverWallet.getCurrency().equalsIgnoreCase(receipt.currency())) {
            throw new IllegalStateException("Currency mismatch: wallets do not match transaction currency " + receipt.currency());
        }

        // 2. Validate Reserve & Cumulative Limit
        if (mode == SettlementMode.STANDARD || mode == SettlementMode.GRACE_SETTLEMENT) {
            BigDecimal newSpent = allowance.getSpentAmount().add(receipt.amount());
            if (newSpent.compareTo(allowance.getAllocatedAmount()) > 0) {
                throw new IllegalStateException("Allowance limit exhausted: spending would exceed allocated amount");
            }
        }

        if (senderWallet.getReservedBalance().compareTo(receipt.amount()) < 0) {
            throw new IllegalStateException("Insufficient reserved balance in wallet " + senderWallet.getId());
        }

        // 3. Atomic Balance Updates
        senderWallet.settleReservedDebit(receipt.amount());
        receiverWallet.credit(receipt.amount());
        walletRepository.save(senderWallet);
        walletRepository.save(receiverWallet);

        // 4. Payment Creation
        String paymentIdempotencyKey = (mode == SettlementMode.PROTECTED_BY_RESERVE)
                ? "offline-protected-tx-" + receipt.txId()
                : "offline-tx-" + receipt.txId();

        Payment payment = new Payment(
                paymentIdempotencyKey,
                senderWallet,
                receiverWallet,
                receipt.amount(),
                receipt.currency()
        );
        payment.markCompleted();
        payment = paymentRepository.save(payment);

        // 5. Double-Entry Ledger Entries (Immutable)
        LedgerEntry debitEntry = new LedgerEntry(
                payment,
                senderWallet,
                EntryType.DEBIT,
                receipt.amount(),
                senderWallet.getBalance()
        );
        LedgerEntry creditEntry = new LedgerEntry(
                payment,
                receiverWallet,
                EntryType.CREDIT,
                receipt.amount(),
                receiverWallet.getBalance()
        );
        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        // 6. Allowance Tracking
        allowance.recordSpent(receipt.amount(), receipt.counter());
        allowanceRepository.save(allowance);

        // 7. Canonical Raw Payload Reconstitution
        String rawPayload = cryptoService.buildTransactionPayload(
                receipt.txId(),
                receipt.allowanceId(),
                receipt.senderWalletId(),
                receipt.receiverWalletId(),
                receipt.amount(),
                receipt.currency(),
                receipt.counter(),
                receipt.nonce()
        );

        // 8. Mode-specific Persistence
        if (mode == SettlementMode.PROTECTED_BY_RESERVE) {
            if (fraudProof == null) {
                throw new IllegalArgumentException("FraudProof cannot be null for PROTECTED_BY_RESERVE settlement");
            }
            OfflineProtectedSettlement protectedSettlement = new OfflineProtectedSettlement(
                    receipt.txId(),
                    fraudProof,
                    payment,
                    receipt.amount(),
                    rawPayload,
                    receipt.deviceSignature()
            );
            protectedSettlementRepository.save(protectedSettlement);

            // Outbox Event for Protected Receipt
            try {
                OfflineReceiptProtectedEvent protectedEvent = new OfflineReceiptProtectedEvent(
                        UUID.randomUUID(),
                        fraudProof.getId(),
                        receipt.txId(),
                        receiverWallet.getId(),
                        senderWallet.getId(),
                        receipt.amount(),
                        "SETTLED_FROM_RESERVE",
                        Instant.now()
                );
                String payloadJson = objectMapper.writeValueAsString(protectedEvent);
                OutboxEvent outboxEvent = new OutboxEvent(
                        "OFFLINE_RECEIPT_PROTECTED",
                        receipt.txId().toString(),
                        "OFFLINE_RECEIPT_PROTECTED",
                        payloadJson
                );
                outboxEventRepository.save(outboxEvent);
            } catch (Exception ex) {
                log.error("Failed to enqueue outbox event for protected receipt {}", receipt.txId(), ex);
                throw new RuntimeException("Outbox persistence failure for protected receipt", ex);
            }
        } else {
            OfflineTransaction offlineTx = new OfflineTransaction(
                    receipt.txId(),
                    allowance,
                    senderWallet,
                    receiverWallet,
                    receipt.amount(),
                    receipt.currency(),
                    receipt.counter(),
                    receipt.nonce(),
                    receipt.deviceSignature(),
                    rawPayload,
                    receipt.createdAt()
            );
            offlineTx.markSettled(payment);
            offlineTransactionRepository.save(offlineTx);

            // Outbox Event for Standard / Grace Settlement
            try {
                PaymentCompletedEvent event = new PaymentCompletedEvent(
                        UUID.randomUUID(),
                        payment.getId(),
                        payment.getIdempotencyKey(),
                        senderWallet.getId(),
                        receiverWallet.getId(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        Instant.now()
                );
                String payloadJson = objectMapper.writeValueAsString(event);
                OutboxEvent outboxEvent = new OutboxEvent(
                        "OFFLINE_PAYMENT",
                        payment.getId().toString(),
                        "OFFLINE_PAYMENT_SETTLED",
                        payloadJson
                );
                outboxEventRepository.save(outboxEvent);
            } catch (Exception ex) {
                log.error("Failed to enqueue outbox event for offline payment {}", receipt.txId(), ex);
                throw new RuntimeException("Outbox persistence failure for offline payment", ex);
            }
        }

        // 8. Update Reputation inside the settlement transaction
        // Lock ordering enforced: allowance row -> wallet rows (canonical UUID order) -> reputation row (locked LAST)
        if (mode == SettlementMode.STANDARD && reputationService != null) {
            reputationService.recordCleanSettlement(receipt, senderWallet, receiverWallet);
        }

        // 9. Post-Commit Cache Eviction
        registerPostCommitEviction(senderWallet.getId(), receiverWallet.getId());

        log.info("Successfully executed atomic settlement [mode={}] for receipt {} (amount: {} {}, payment: {})",
                mode, receipt.txId(), receipt.amount(), receipt.currency(), payment.getId());

        return payment;
    }

    public void registerPostCommitEviction(UUID wallet1, UUID wallet2) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    walletBalanceCacheService.evictBalance(wallet1);
                    walletBalanceCacheService.evictBalance(wallet2);
                }
            });
        } else {
            walletBalanceCacheService.evictBalance(wallet1);
            walletBalanceCacheService.evictBalance(wallet2);
        }
    }
}
