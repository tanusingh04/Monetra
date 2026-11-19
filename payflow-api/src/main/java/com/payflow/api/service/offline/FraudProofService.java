package com.payflow.api.service.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.offline.OfflineReceiptDto;
import com.payflow.api.metrics.PayFlowMetricsService;
import com.payflow.api.service.WalletBalanceCacheService;
import com.payflow.core.domain.offline.*;
import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.domain.wallet.WalletStatus;
import com.payflow.core.event.OfflineFraudProofDetectedEvent;
import com.payflow.core.exception.ResourceNotFoundException;
import com.payflow.core.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class FraudProofService {

    private static final Logger log = LoggerFactory.getLogger(FraudProofService.class);

    private final FraudProofRepository fraudProofRepository;
    private final OfflineAllowanceRepository allowanceRepository;
    private final DeviceEnrollmentRepository deviceEnrollmentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final OfflineCryptoService cryptoService;
    private final WalletBalanceCacheService walletBalanceCacheService;
    private final PayFlowMetricsService metricsService;
    private final ObjectMapper objectMapper;
    private final WalletRepository walletRepository;
    private final OfflineAtomicSettlementExecutor atomicSettlementExecutor;
    private ReputationService reputationService;

    public FraudProofService(FraudProofRepository fraudProofRepository,
                             OfflineAllowanceRepository allowanceRepository,
                             DeviceEnrollmentRepository deviceEnrollmentRepository,
                             OutboxEventRepository outboxEventRepository,
                             OfflineCryptoService cryptoService,
                             WalletBalanceCacheService walletBalanceCacheService,
                             @Autowired(required = false) PayFlowMetricsService metricsService,
                             ObjectMapper objectMapper) {
        this(fraudProofRepository, allowanceRepository, deviceEnrollmentRepository,
                outboxEventRepository, cryptoService, walletBalanceCacheService,
                metricsService, objectMapper, null, null, null);
    }

    public FraudProofService(FraudProofRepository fraudProofRepository,
                             OfflineAllowanceRepository allowanceRepository,
                             DeviceEnrollmentRepository deviceEnrollmentRepository,
                             OutboxEventRepository outboxEventRepository,
                             OfflineCryptoService cryptoService,
                             WalletBalanceCacheService walletBalanceCacheService,
                             @Autowired(required = false) PayFlowMetricsService metricsService,
                             ObjectMapper objectMapper,
                             @Autowired(required = false) WalletRepository walletRepository,
                             @Autowired(required = false) OfflineAtomicSettlementExecutor atomicSettlementExecutor) {
        this(fraudProofRepository, allowanceRepository, deviceEnrollmentRepository,
                outboxEventRepository, cryptoService, walletBalanceCacheService,
                metricsService, objectMapper, walletRepository, atomicSettlementExecutor, null);
    }

    @Autowired
    public FraudProofService(FraudProofRepository fraudProofRepository,
                             OfflineAllowanceRepository allowanceRepository,
                             DeviceEnrollmentRepository deviceEnrollmentRepository,
                             OutboxEventRepository outboxEventRepository,
                             OfflineCryptoService cryptoService,
                             WalletBalanceCacheService walletBalanceCacheService,
                             @Autowired(required = false) PayFlowMetricsService metricsService,
                             ObjectMapper objectMapper,
                             @Autowired(required = false) WalletRepository walletRepository,
                             @Autowired(required = false) OfflineAtomicSettlementExecutor atomicSettlementExecutor,
                             @Autowired(required = false) ReputationService reputationService) {
        this.fraudProofRepository = fraudProofRepository;
        this.allowanceRepository = allowanceRepository;
        this.deviceEnrollmentRepository = deviceEnrollmentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.cryptoService = cryptoService;
        this.walletBalanceCacheService = walletBalanceCacheService;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
        this.walletRepository = walletRepository;
        this.atomicSettlementExecutor = atomicSettlementExecutor;
        this.reputationService = reputationService;
    }

    public void setReputationService(ReputationService reputationService) {
        this.reputationService = reputationService;
    }

    public record FraudEvaluationResult(
            boolean isEquivocation,
            boolean signaturesValid,
            FraudProof proof,
            boolean secondReceiptSettled,
            UUID settlementPaymentId,
            java.math.BigDecimal uncoveredAmount,
            String rejectionReason
    ) {
        public static FraudEvaluationResult detected(FraudProof proof) {
            return new FraudEvaluationResult(true, true, proof, false, null, java.math.BigDecimal.ZERO, null);
        }

        public static FraudEvaluationResult protectedSettled(FraudProof proof, UUID paymentId) {
            return new FraudEvaluationResult(true, true, proof, true, paymentId, java.math.BigDecimal.ZERO, null);
        }

        public static FraudEvaluationResult uncovered(FraudProof proof, java.math.BigDecimal uncoveredAmount) {
            return new FraudEvaluationResult(true, true, proof, false, null, uncoveredAmount,
                    "FRAUD_PROOF_DETECTED:" + proof.getId() + ":UNCOVERED_BY_RESERVE");
        }

        public static FraudEvaluationResult invalidSignature(String reason) {
            return new FraudEvaluationResult(true, false, null, false, null, java.math.BigDecimal.ZERO, reason);
        }

        public static FraudEvaluationResult identical(String reason) {
            return new FraudEvaluationResult(false, true, null, false, null, java.math.BigDecimal.ZERO, reason);
        }
    }

    public record FraudProofVerificationResult(
            UUID proofId,
            boolean isValidProof,
            boolean firstSignatureValid,
            boolean secondSignatureValid,
            Long counter,
            UUID firstTxId,
            UUID secondTxId,
            String deviceId,
            String details
    ) {}

    /**
     * Evaluates a counter collision between a new receipt and an existing transaction.
     * If both receipts are signed by the device but have different payloads,
     * an equivocation fraud proof is created, and the device and allowance are revoked.
     */
    @Transactional
    public FraudEvaluationResult handleCounterConflict(OfflineReceiptDto newReceipt,
                                                        OfflineTransaction existingTx,
                                                        OfflineAllowance allowance,
                                                        DeviceEnrollment device) {
        // 1. Reconstruct or retrieve payloads
        String firstPayload = existingTx.getReceiptPayload();
        if (firstPayload == null || firstPayload.isBlank()) {
            firstPayload = cryptoService.buildTransactionPayload(
                    existingTx.getId(),
                    existingTx.getAllowance().getId(),
                    existingTx.getSenderWallet().getId(),
                    existingTx.getReceiverWallet().getId(),
                    existingTx.getAmount(),
                    existingTx.getCurrency(),
                    existingTx.getCounter(),
                    existingTx.getNonce()
            );
        }

        String secondPayload = cryptoService.buildTransactionPayload(
                newReceipt.txId(),
                newReceipt.allowanceId(),
                newReceipt.senderWalletId(),
                newReceipt.receiverWalletId(),
                newReceipt.amount(),
                newReceipt.currency(),
                newReceipt.counter(),
                newReceipt.nonce()
        );

        // 2. Check if identical receipt (idempotent replay, not fraud)
        if (existingTx.getId().equals(newReceipt.txId()) || firstPayload.equals(secondPayload)) {
            log.info("Identical receipt resubmitted for allowance {} and counter {}. Not an equivocation.",
                    allowance.getId(), newReceipt.counter());
            return FraudEvaluationResult.identical("Identical receipt already submitted");
        }

        // 3. Cryptographically verify BOTH signatures (Anti-framing rule)
        // If an attacker fabricated the second receipt, its signature will be invalid.
        // We must NEVER frame an innocent user.
        boolean firstSigValid = cryptoService.verifyDeviceSignature(
                firstPayload, existingTx.getDeviceSignature(), device.getPublicKey());
        boolean secondSigValid = cryptoService.verifyDeviceSignature(
                secondPayload, newReceipt.deviceSignature(), device.getPublicKey());

        if (!firstSigValid || !secondSigValid) {
            log.warn("Counter collision on allowance {} counter {} rejected: invalid signature (firstSigValid={}, secondSigValid={})",
                    allowance.getId(), newReceipt.counter(), firstSigValid, secondSigValid);
            if (metricsService != null) {
                metricsService.incrementFraudProofsRejectedInvalidSignature();
            }
            return FraudEvaluationResult.invalidSignature(
                    "Conflicting receipt signature verification failed. Anti-framing protection triggered.");
        }

        // 4. Valid Equivocation Confirmed!
        // Lock ordering to avoid deadlocks: allowance row -> wallet rows (canonical UUID order)
        OfflineAllowance lockedAllowance = allowanceRepository.findByIdForUpdate(allowance.getId())
                .orElse(allowance);

        // Check if a fraud proof for this counter on this allowance was already registered concurrently
        Optional<FraudProof> existingCounterProof = fraudProofRepository.findFirstByAllowanceIdAndCounter(
                lockedAllowance.getId(), newReceipt.counter());
        if (existingCounterProof.isPresent()) {
            log.info("Fraud proof already exists for allowance {} counter {}. Treating concurrent receipt as uncovered.",
                    lockedAllowance.getId(), newReceipt.counter());
            return FraudEvaluationResult.uncovered(existingCounterProof.get(), newReceipt.amount());
        }

        log.error("CRITICAL: Offline double-spend fraud proof confirmed for allowance {} on counter {} by device {}",
                lockedAllowance.getId(), newReceipt.counter(), device.getDeviceId());

        FraudProof proof;
        Optional<FraudProof> existingProof = fraudProofRepository.findByAllowanceIdAndCounterAndFirstTxIdAndSecondTxId(
                lockedAllowance.getId(), newReceipt.counter(), existingTx.getId(), newReceipt.txId());

        if (existingProof.isPresent()) {
            proof = existingProof.get();
        } else {
            try {
                proof = new FraudProof(
                        lockedAllowance,
                        device.getDeviceId(),
                        lockedAllowance.getWallet(),
                        newReceipt.counter(),
                        existingTx.getId(),
                        newReceipt.txId(),
                        firstPayload,
                        existingTx.getDeviceSignature(),
                        secondPayload,
                        newReceipt.deviceSignature()
                );
                proof = fraudProofRepository.save(proof);
            } catch (DataIntegrityViolationException ex) {
                // Concurrency: another thread created the proof concurrently
                log.info("Concurrent fraud proof insertion detected, retrieving existing proof: {}", ex.getMessage());
                proof = fraudProofRepository.findFirstByAllowanceIdAndCounter(lockedAllowance.getId(), newReceipt.counter())
                        .orElseThrow(() -> ex);
            }
        }

        // 5. In the SAME transaction: Revoke Allowance & DeviceEnrollment
        lockedAllowance.markRevoked(RevocationReason.EQUIVOCATION_FRAUD);
        allowanceRepository.save(lockedAllowance);

        device.revoke(RevocationReason.EQUIVOCATION_FRAUD);
        deviceEnrollmentRepository.save(device);

        if (reputationService != null && lockedAllowance.getUser() != null) {
            reputationService.recordFraudDetected(lockedAllowance.getUser().getId(), proof.getId());
        }

        // 6. Receiver Protection Check:
        // if ALL hold — receipt amount <= allowance maxSingleTx, allowance remaining (allocated - spent) >= amount,
        // sender reserved balance >= amount, receiver wallet ACTIVE, currency matches, receipt not past expiry
        // — then SETTLE the second receipt from sender's reserve via performAtomicSettlement with mode PROTECTED_BY_RESERVE.
        boolean covered = false;
        Payment settlementPayment = null;
        java.math.BigDecimal uncoveredAmount = java.math.BigDecimal.ZERO;

        Wallet senderWallet = (walletRepository != null) ? walletRepository.findById(allowance.getWallet().getId()).orElse(null) : allowance.getWallet();
        Wallet receiverWallet = (walletRepository != null) ? walletRepository.findById(newReceipt.receiverWalletId()).orElse(null) : null;

        boolean amountWithinLimit = newReceipt.amount().compareTo(allowance.getMaxSingleTxAmount()) <= 0;
        java.math.BigDecimal remainingAllowance = allowance.getAllocatedAmount().subtract(allowance.getSpentAmount());
        boolean allowanceCovers = remainingAllowance.compareTo(newReceipt.amount()) >= 0;
        boolean reserveCovers = senderWallet != null && senderWallet.getReservedBalance().compareTo(newReceipt.amount()) >= 0;
        boolean receiverActive = receiverWallet != null && receiverWallet.getStatus() == WalletStatus.ACTIVE;
        boolean currencyMatches = senderWallet != null && receiverWallet != null &&
                senderWallet.getCurrency().equalsIgnoreCase(newReceipt.currency()) &&
                receiverWallet.getCurrency().equalsIgnoreCase(newReceipt.currency());
        boolean notExpired = !newReceipt.createdAt().isAfter(allowance.getExpiresAt());

        if (atomicSettlementExecutor != null && amountWithinLimit && allowanceCovers && reserveCovers &&
                receiverActive && currencyMatches && notExpired) {
            try {
                settlementPayment = atomicSettlementExecutor.performAtomicSettlement(
                        newReceipt, allowance, SettlementMode.PROTECTED_BY_RESERVE, proof);
                covered = true;
                proof.recordProtectionOutcome(true, newReceipt.amount(), java.math.BigDecimal.ZERO, settlementPayment.getId());
                proof = fraudProofRepository.save(proof);
                log.info("Protected second receipt {} settled from reserve of wallet {} to wallet {}",
                        newReceipt.txId(), senderWallet.getId(), receiverWallet.getId());
            } catch (Exception ex) {
                log.error("Failed to execute protected settlement for second receipt {}: {}", newReceipt.txId(), ex.getMessage(), ex);
                covered = false;
            }
        }

        if (!covered) {
            uncoveredAmount = newReceipt.amount();
            proof.recordProtectionOutcome(false, java.math.BigDecimal.ZERO, uncoveredAmount, null);
            proof = fraudProofRepository.save(proof);

            // Outbox Event: OfflineReceiptUncoveredEvent
            try {
                com.payflow.core.event.OfflineReceiptUncoveredEvent uncoveredEvent = new com.payflow.core.event.OfflineReceiptUncoveredEvent(
                        UUID.randomUUID(),
                        proof.getId(),
                        newReceipt.txId(),
                        newReceipt.receiverWalletId(),
                        newReceipt.senderWalletId(),
                        newReceipt.amount(),
                        uncoveredAmount,
                        "UNCOVERED_BY_RESERVE",
                        Instant.now()
                );
                String eventJson = objectMapper.writeValueAsString(uncoveredEvent);
                OutboxEvent outboxEvent = new OutboxEvent(
                        "OFFLINE_RECEIPT_UNCOVERED",
                        newReceipt.txId().toString(),
                        "OFFLINE_RECEIPT_UNCOVERED",
                        eventJson
                );
                outboxEventRepository.save(outboxEvent);
            } catch (Exception e) {
                log.error("Failed to enqueue outbox event for uncovered receipt {}", newReceipt.txId(), e);
                throw new RuntimeException("Outbox persistence failure for uncovered receipt", e);
            }
        }

        // 7. Record Outbox Event for Fraud Proof
        try {
            UUID proofId = proof.getId() != null ? proof.getId() : UUID.randomUUID();
            OfflineFraudProofDetectedEvent event = new OfflineFraudProofDetectedEvent(
                    UUID.randomUUID(),
                    proofId,
                    allowance.getId(),
                    allowance.getWallet().getId(),
                    device.getDeviceId(),
                    newReceipt.counter(),
                    existingTx.getId(),
                    newReceipt.txId(),
                    covered,
                    covered ? newReceipt.amount() : java.math.BigDecimal.ZERO,
                    uncoveredAmount,
                    Instant.now()
            );
            String eventJson = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = new OutboxEvent(
                    "OFFLINE_FRAUD_PROOF",
                    proofId.toString(),
                    "OFFLINE_FRAUD_PROOF_DETECTED",
                    eventJson
            );
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to enqueue outbox event for fraud proof {}", proof.getId(), e);
            throw new RuntimeException("Outbox persistence failure for fraud proof", e);
        }

        // 8. Increment metric
        if (metricsService != null) {
            metricsService.incrementFraudProofsDetected();
        }

        // 9. Register post-commit cache invalidation
        registerPostCommitEviction(allowance.getWallet().getId());

        if (covered && settlementPayment != null) {
            return FraudEvaluationResult.protectedSettled(proof, settlementPayment.getId());
        } else {
            return FraudEvaluationResult.uncovered(proof, uncoveredAmount);
        }
    }

    @Transactional(readOnly = true)
    public Page<FraudProof> getFraudProofs(FraudProofStatus status, Pageable pageable) {
        if (status != null) {
            return fraudProofRepository.findByStatus(status, pageable);
        }
        return fraudProofRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public FraudProof getFraudProof(UUID id) {
        return fraudProofRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fraud proof not found: " + id));
    }

    @Transactional
    public FraudProof reviewFraudProof(UUID id, FraudProofStatus decision, String reviewer, String note) {
        FraudProof proof = getFraudProof(id);
        proof.review(decision, reviewer, note);
        FraudProof saved = fraudProofRepository.save(proof);

        if (reputationService != null && proof.getAllowance() != null && proof.getAllowance().getUser() != null) {
            UUID userId = proof.getAllowance().getUser().getId();
            if (decision == FraudProofStatus.CONFIRMED) {
                reputationService.recordFraudConfirmed(userId, proof.getId());
            } else if (decision == FraudProofStatus.DISMISSED) {
                reputationService.restoreDismissedProof(userId, proof.getId());
            }
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public FraudProofVerificationResult verifyFraudProof(UUID id) {
        FraudProof proof = getFraudProof(id);

        DeviceEnrollment device = deviceEnrollmentRepository.findByDeviceId(proof.getDeviceId())
                .orElse(null);

        if (device == null) {
            return new FraudProofVerificationResult(
                    proof.getId(),
                    false,
                    false,
                    false,
                    proof.getCounter(),
                    proof.getFirstTxId(),
                    proof.getSecondTxId(),
                    proof.getDeviceId(),
                    "Device enrollment not found for deviceId: " + proof.getDeviceId()
            );
        }

        boolean firstSigValid = cryptoService.verifyDeviceSignature(
                proof.getFirstReceiptPayload(), proof.getFirstSignature(), device.getPublicKey());
        boolean secondSigValid = cryptoService.verifyDeviceSignature(
                proof.getSecondReceiptPayload(), proof.getSecondSignature(), device.getPublicKey());
        boolean payloadsDiffer = !proof.getFirstReceiptPayload().equals(proof.getSecondReceiptPayload());

        boolean isValidProof = firstSigValid && secondSigValid && payloadsDiffer;

        String details = isValidProof
                ? "Cryptographic proof independently verified: both signatures valid under public key, payloads differ on same counter."
                : String.format("Verification failed: firstSig=%b, secondSig=%b, payloadsDiffer=%b",
                firstSigValid, secondSigValid, payloadsDiffer);

        return new FraudProofVerificationResult(
                proof.getId(),
                isValidProof,
                firstSigValid,
                secondSigValid,
                proof.getCounter(),
                proof.getFirstTxId(),
                proof.getSecondTxId(),
                proof.getDeviceId(),
                details
        );
    }

    private void registerPostCommitEviction(UUID walletId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    walletBalanceCacheService.evictBalance(walletId);
                }
            });
        } else {
            walletBalanceCacheService.evictBalance(walletId);
        }
    }
}
