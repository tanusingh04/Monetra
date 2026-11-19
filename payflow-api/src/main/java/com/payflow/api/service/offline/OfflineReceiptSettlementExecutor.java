package com.payflow.api.service.offline;

import com.payflow.api.dto.offline.OfflineReceiptDto;
import com.payflow.api.dto.offline.OfflineSyncItemResult;
import com.payflow.core.domain.offline.*;
import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.domain.wallet.WalletStatus;
import com.payflow.core.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class OfflineReceiptSettlementExecutor {

    private static final Logger log = LoggerFactory.getLogger(OfflineReceiptSettlementExecutor.class);

    private final OfflineTransactionRepository offlineTransactionRepository;
    private final OfflineProtectedSettlementRepository protectedSettlementRepository;
    private final OfflineRejectedReceiptRepository rejectedReceiptRepository;
    private final OfflineAllowanceRepository allowanceRepository;
    private final DeviceEnrollmentRepository deviceEnrollmentRepository;
    private final WalletRepository walletRepository;
    private final OfflineCryptoService cryptoService;
    private final FraudProofService fraudProofService;
    private final OfflineAtomicSettlementExecutor atomicSettlementExecutor;
    private final ReputationService reputationService;

    @org.springframework.beans.factory.annotation.Autowired
    public OfflineReceiptSettlementExecutor(OfflineTransactionRepository offlineTransactionRepository,
                                           OfflineProtectedSettlementRepository protectedSettlementRepository,
                                           OfflineRejectedReceiptRepository rejectedReceiptRepository,
                                           OfflineAllowanceRepository allowanceRepository,
                                           DeviceEnrollmentRepository deviceEnrollmentRepository,
                                           WalletRepository walletRepository,
                                           OfflineCryptoService cryptoService,
                                           FraudProofService fraudProofService,
                                           OfflineAtomicSettlementExecutor atomicSettlementExecutor,
                                           @org.springframework.beans.factory.annotation.Autowired(required = false)
                                           ReputationService reputationService) {
        this.offlineTransactionRepository = offlineTransactionRepository;
        this.protectedSettlementRepository = protectedSettlementRepository;
        this.rejectedReceiptRepository = rejectedReceiptRepository;
        this.allowanceRepository = allowanceRepository;
        this.deviceEnrollmentRepository = deviceEnrollmentRepository;
        this.walletRepository = walletRepository;
        this.cryptoService = cryptoService;
        this.fraudProofService = fraudProofService;
        this.atomicSettlementExecutor = atomicSettlementExecutor;
        this.reputationService = reputationService;
    }

    public OfflineReceiptSettlementExecutor(OfflineTransactionRepository offlineTransactionRepository,
                                           OfflineProtectedSettlementRepository protectedSettlementRepository,
                                           OfflineRejectedReceiptRepository rejectedReceiptRepository,
                                           OfflineAllowanceRepository allowanceRepository,
                                           DeviceEnrollmentRepository deviceEnrollmentRepository,
                                           WalletRepository walletRepository,
                                           OfflineCryptoService cryptoService,
                                           FraudProofService fraudProofService,
                                           OfflineAtomicSettlementExecutor atomicSettlementExecutor) {
        this(offlineTransactionRepository, protectedSettlementRepository, rejectedReceiptRepository,
                allowanceRepository, deviceEnrollmentRepository, walletRepository, cryptoService,
                fraudProofService, atomicSettlementExecutor, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OfflineSyncItemResult settleSingleReceiptIsolated(OfflineReceiptDto receipt) {
        // 1. Idempotency Check: Avoid duplicate processing of already settled or rejected receipts
        Optional<OfflineTransaction> existingOpt = offlineTransactionRepository.findById(receipt.txId());
        if (existingOpt.isPresent()) {
            OfflineTransaction existing = existingOpt.get();
            if (existing.getStatus() == OfflineTransactionStatus.SETTLED) {
                log.info("Offline receipt {} is already settled. Replaying payment ID {}", receipt.txId(), existing.getPayment().getId());
                return new OfflineSyncItemResult(receipt.txId(), "ALREADY_SETTLED", existing.getPayment().getId(), "Transaction already settled");
            } else {
                log.warn("Offline receipt {} was previously rejected: {}", receipt.txId(), existing.getRejectionReason());
                return new OfflineSyncItemResult(receipt.txId(), "REJECTED", null, existing.getRejectionReason());
            }
        }

        // Check if settled via protected settlements table
        Optional<OfflineProtectedSettlement> existingProtected = protectedSettlementRepository.findById(receipt.txId());
        if (existingProtected.isPresent()) {
            OfflineProtectedSettlement protectedSettlement = existingProtected.get();
            log.info("Offline receipt {} was already settled via protected reserve. Payment ID: {}", receipt.txId(), protectedSettlement.getPayment().getId());
            return new OfflineSyncItemResult(receipt.txId(), "ALREADY_SETTLED", protectedSettlement.getPayment().getId(), "Transaction already settled via protected reserve");
        }

        // Check if previously rejected in rejected_receipts table
        Optional<OfflineRejectedReceipt> existingRejected = rejectedReceiptRepository.findById(receipt.txId());
        if (existingRejected.isPresent()) {
            OfflineRejectedReceipt rejected = existingRejected.get();
            log.warn("Offline receipt {} was previously recorded as rejected: {}", receipt.txId(), rejected.getRejectionReason());
            return new OfflineSyncItemResult(receipt.txId(), "REJECTED", null, rejected.getRejectionReason());
        }

        // 2. Fetch and Validate Allowance
        Optional<OfflineAllowance> allowanceOpt = allowanceRepository.findByIdForUpdate(receipt.allowanceId());
        if (allowanceOpt.isEmpty()) {
            return recordRejection(receipt, null, "Allowance not found: " + receipt.allowanceId(), RejectionCategory.SYSTEM_ERROR);
        }

        OfflineAllowance allowance = allowanceOpt.get();

        // 3. Fetch Sender Device Public Key
        Optional<DeviceEnrollment> deviceOpt = deviceEnrollmentRepository.findByUserIdAndDeviceId(
                allowance.getUser().getId(), allowance.getDeviceId());
        if (deviceOpt.isEmpty()) {
            return recordRejection(receipt, allowance, "Sender device not enrolled", RejectionCategory.SENDER_ATTRIBUTABLE);
        }

        DeviceEnrollment device = deviceOpt.get();

        // Verify Device Signature first (anti-framing & authenticity)
        boolean validSignature = cryptoService.verifyDeviceSignature(
                receipt.txId(),
                receipt.allowanceId(),
                receipt.senderWalletId(),
                receipt.receiverWalletId(),
                receipt.amount(),
                receipt.currency(),
                receipt.counter(),
                receipt.nonce(),
                receipt.deviceSignature(),
                device.getPublicKey()
        );

        if (!validSignature) {
            log.error("Cryptographic signature verification failed for offline receipt {}", receipt.txId());
            return recordRejection(receipt, allowance, "Invalid cryptographic device signature", RejectionCategory.SENDER_ATTRIBUTABLE);
        }

        // 4. Counter Monotonicity, Replay Protection & Equivocation (Fraud Proof) Detection
        // Must be evaluated BEFORE limits, because an equivocation proof must be created even if
        // the conflicting receipt exceeds single limit or remaining allowance (it will be recorded as uncovered).
        Optional<OfflineTransaction> existingCounterTx = offlineTransactionRepository.findByAllowanceIdAndCounter(receipt.allowanceId(), receipt.counter());
        if (existingCounterTx.isPresent()) {
            OfflineTransaction existing = existingCounterTx.get();
            if (existing.getId().equals(receipt.txId())) {
                if (existing.getStatus() == OfflineTransactionStatus.SETTLED) {
                    log.info("Offline receipt {} is already settled. Replaying payment ID {}", receipt.txId(), existing.getPayment().getId());
                    return new OfflineSyncItemResult(receipt.txId(), "ALREADY_SETTLED", existing.getPayment().getId(), "Transaction already settled");
                } else {
                    return new OfflineSyncItemResult(receipt.txId(), "REJECTED", null, existing.getRejectionReason());
                }
            }

            // Conflicting receipt on the same counter (potential equivocation / fraud proof)
            FraudProofService.FraudEvaluationResult fraudResult = fraudProofService.handleCounterConflict(
                    receipt, existing, allowance, device);

            if (fraudResult.signaturesValid() && fraudResult.isEquivocation() && fraudResult.proof() != null) {
                if (fraudResult.secondReceiptSettled()) {
                    log.info("Conflicting receipt {} on counter {} settled via receiver-protected reserve", receipt.txId(), receipt.counter());
                    return new OfflineSyncItemResult(receipt.txId(), "SETTLED", fraudResult.settlementPaymentId(),
                            "Settled from reserved funds via receiver protection (fraud proof: " + fraudResult.proof().getId() + ")");
                } else {
                    return recordRejection(receipt, allowance,
                            "FRAUD_PROOF_DETECTED:" + fraudResult.proof().getId() + ":UNCOVERED_BY_RESERVE",
                            RejectionCategory.SENDER_ATTRIBUTABLE);
                }
            } else if (!fraudResult.signaturesValid()) {
                return recordRejection(receipt, allowance, "Invalid cryptographic device signature on conflicting counter", RejectionCategory.SENDER_ATTRIBUTABLE);
            } else {
                return recordRejection(receipt, allowance, "Replay attack detected: Counter " + receipt.counter() + " has already been settled for allowance " + receipt.allowanceId(), RejectionCategory.SENDER_ATTRIBUTABLE);
            }
        }

        // 5. Revocation & Grace Settlement Handling
        boolean isAllowanceRevoked = allowance.getStatus() == AllowanceStatus.REVOKED;
        boolean isDeviceRevoked = !device.isActive();

        if (isAllowanceRevoked || isDeviceRevoked) {
            RevocationReason reason = allowance.getRevocationReason() != null
                    ? allowance.getRevocationReason() : device.getRevocationReason();

            if (reason == RevocationReason.EQUIVOCATION_FRAUD) {
                // Grace settlement for honest receivers on fraud revocation:
                if (receipt.createdAt().isAfter(allowance.getExpiresAt())) {
                    return recordRejection(receipt, allowance, "Transaction receipt was generated after allowance expired", RejectionCategory.SENDER_ATTRIBUTABLE);
                }

                if (receipt.amount().compareTo(allowance.getMaxSingleTxAmount()) > 0) {
                    return recordRejection(receipt, allowance, "Amount " + receipt.amount() + " exceeds maximum single transaction limit of " + allowance.getMaxSingleTxAmount(), RejectionCategory.SENDER_ATTRIBUTABLE);
                }

                Wallet senderWallet = walletRepository.findById(receipt.senderWalletId()).orElse(null);
                java.math.BigDecimal remainingAllowance = allowance.getAllocatedAmount().subtract(allowance.getSpentAmount());

                boolean coversAllowance = remainingAllowance.compareTo(receipt.amount()) >= 0;
                boolean coversReserve = senderWallet != null && senderWallet.getReservedBalance().compareTo(receipt.amount()) >= 0;

                if (coversAllowance && coversReserve) {
                    try {
                        Payment payment = atomicSettlementExecutor.performAtomicSettlement(
                                receipt, allowance, SettlementMode.GRACE_SETTLEMENT, null);
                        return new OfflineSyncItemResult(receipt.txId(), "SETTLED", payment.getId(), "Settled under grace period for honest receiver");
                    } catch (Exception ex) {
                        return recordRejection(receipt, allowance, "Grace settlement execution failed: " + ex.getMessage(), RejectionCategory.SYSTEM_ERROR);
                    }
                } else {
                    return recordRejection(receipt, allowance, "UNCOVERED_BY_RESERVE: Insufficient remaining reserve for grace settlement", RejectionCategory.SENDER_ATTRIBUTABLE);
                }
            } else {
                // Any other revocation reason (ADMIN, DEVICE_LOST, OTHER) rejects strictly
                String revMsg = "Allowance has been revoked";
                if (reason == RevocationReason.DEVICE_LOST) {
                    revMsg = "Device reported lost or stolen";
                } else if (reason == RevocationReason.ADMIN) {
                    revMsg = "Allowance administratively revoked";
                }
                return recordRejection(receipt, allowance, revMsg, RejectionCategory.SENDER_ATTRIBUTABLE);
            }
        }

        // 6. Validation for Active Allowance
        if (receipt.createdAt().isAfter(allowance.getExpiresAt())) {
            return recordRejection(receipt, allowance, "Transaction receipt was generated after allowance expired", RejectionCategory.SENDER_ATTRIBUTABLE);
        }

        if (receipt.amount().compareTo(allowance.getMaxSingleTxAmount()) > 0) {
            return recordRejection(receipt, allowance, "Amount " + receipt.amount() + " exceeds maximum single transaction limit of " + allowance.getMaxSingleTxAmount(), RejectionCategory.SENDER_ATTRIBUTABLE);
        }

        if (!receipt.senderWalletId().equals(allowance.getWallet().getId())) {
            return recordRejection(receipt, allowance, "Sender wallet does not match allowance wallet", RejectionCategory.SENDER_ATTRIBUTABLE);
        }

        // 7. Cumulative Spending Limit Check
        if (allowance.getSpentAmount().add(receipt.amount()).compareTo(allowance.getAllocatedAmount()) > 0) {
            return recordRejection(receipt, allowance, "Allowance limit exhausted: Cumulative spending would exceed allocated amount", RejectionCategory.SENDER_ATTRIBUTABLE);
        }

        // 8. Standard Atomic Settlement
        try {
            Payment payment = atomicSettlementExecutor.performAtomicSettlement(
                    receipt, allowance, SettlementMode.STANDARD, null);
            return new OfflineSyncItemResult(receipt.txId(), "SETTLED", payment.getId(), "Successfully settled");
        } catch (IllegalStateException e) {
            RejectionCategory cat = e.getMessage().contains("Receiver wallet") || e.getMessage().contains("Currency mismatch")
                    ? RejectionCategory.RECEIVER_RELATED : RejectionCategory.SENDER_ATTRIBUTABLE;
            return recordRejection(receipt, allowance, e.getMessage(), cat);
        } catch (Exception e) {
            log.error("Settlement failure for receipt {}: {}", receipt.txId(), e.getMessage(), e);
            return recordRejection(receipt, allowance, "Settlement failed: " + e.getMessage(), RejectionCategory.SYSTEM_ERROR);
        }
    }

    public OfflineSyncItemResult recordRejection(OfflineReceiptDto receipt, OfflineAllowance allowance, String reason, RejectionCategory category) {
        log.warn("Offline receipt {} rejected [{}]: {}", receipt.txId(), category, reason);

        UUID allowanceId = allowance != null ? allowance.getId() : receipt.allowanceId();

        // If counter is not used in offline_transactions, save to offline_transactions
        if (allowance != null && !offlineTransactionRepository.existsByAllowanceIdAndCounter(allowanceId, receipt.counter())) {
            Wallet sender = walletRepository.findById(receipt.senderWalletId()).orElse(null);
            Wallet receiver = walletRepository.findById(receipt.receiverWalletId()).orElse(null);

            if (sender != null && receiver != null) {
                try {
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
                    OfflineTransaction offlineTx = new OfflineTransaction(
                            receipt.txId(),
                            allowance,
                            sender,
                            receiver,
                            receipt.amount(),
                            receipt.currency(),
                            receipt.counter(),
                            receipt.nonce(),
                            receipt.deviceSignature(),
                            rawPayload,
                            receipt.createdAt()
                    );
                    offlineTx.markRejected(reason, category);
                    offlineTransactionRepository.save(offlineTx);
                } catch (Exception ex) {
                    log.warn("Failed to persist rejected offline_transaction {}: {}", receipt.txId(), ex.getMessage());
                }
            }
        } else {
            // Counter already exists on offline_transactions! Persist in offline_rejected_receipts
            try {
                OfflineRejectedReceipt rejectedReceipt = new OfflineRejectedReceipt(
                        receipt.txId(),
                        allowanceId,
                        receipt.senderWalletId(),
                        receipt.receiverWalletId(),
                        receipt.amount(),
                        receipt.currency(),
                        receipt.counter(),
                        reason,
                        category
                );
                rejectedReceiptRepository.save(rejectedReceipt);
            } catch (Exception ex) {
                log.warn("Failed to persist rejected receipt in offline_rejected_receipts {}: {}", receipt.txId(), ex.getMessage());
            }
        }

        if (category == RejectionCategory.SENDER_ATTRIBUTABLE && !reason.startsWith("FRAUD_PROOF_DETECTED") && reputationService != null) {
            UUID senderUserId = null;
            if (allowance != null && allowance.getUser() != null) {
                senderUserId = allowance.getUser().getId();
            } else {
                Wallet sender = walletRepository.findById(receipt.senderWalletId()).orElse(null);
                if (sender != null && sender.getUser() != null) {
                    senderUserId = sender.getUser().getId();
                }
            }
            if (senderUserId != null) {
                try {
                    reputationService.recordRejectionPenalty(senderUserId, receipt.txId().toString(), reason, category);
                } catch (Exception ex) {
                    log.warn("Failed to record reputation rejection penalty for user {}: {}", senderUserId, ex.getMessage());
                }
            }
        }

        return new OfflineSyncItemResult(receipt.txId(), "REJECTED", null, reason);
    }
}
