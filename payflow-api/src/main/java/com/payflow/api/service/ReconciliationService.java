package com.payflow.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.reconciliation.ReconciliationReportResponse;
import com.payflow.core.domain.ledger.EntryType;
import com.payflow.core.domain.ledger.LedgerEntry;
import com.payflow.core.domain.merkle.LedgerMerkleBatch;
import com.payflow.core.domain.merkle.LedgerMerkleLeaf;
import com.payflow.core.domain.offline.AllowanceStatus;
import com.payflow.core.domain.offline.FraudProof;
import com.payflow.core.domain.offline.FraudProofStatus;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.offline.OfflineTransaction;
import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.domain.reconciliation.ReconciliationReport;
import com.payflow.core.domain.reconciliation.ReconciliationStatus;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.merkle.CanonicalLedgerEntry;
import com.payflow.core.merkle.MerkleTree;
import com.payflow.core.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final ReconciliationReportRepository reportRepository;
    private final FraudProofRepository fraudProofRepository;
    private final OfflineAllowanceRepository allowanceRepository;
    private final OfflineTransactionRepository offlineTransactionRepository;
    private final OfflineProtectedSettlementRepository protectedSettlementRepository;
    private final LedgerMerkleBatchRepository merkleBatchRepository;
    private final LedgerMerkleLeafRepository merkleLeafRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final com.payflow.api.metrics.PayFlowMetricsService metricsService;

    public ReconciliationService(LedgerEntryRepository ledgerEntryRepository,
                                 WalletRepository walletRepository,
                                 PaymentRepository paymentRepository,
                                 ReconciliationReportRepository reportRepository,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                 FraudProofRepository fraudProofRepository,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                 OfflineAllowanceRepository allowanceRepository,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                 OfflineTransactionRepository offlineTransactionRepository,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                 OfflineProtectedSettlementRepository protectedSettlementRepository,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                 LedgerMerkleBatchRepository merkleBatchRepository,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                 LedgerMerkleLeafRepository merkleLeafRepository,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                 OutboxEventRepository outboxEventRepository,
                                 ObjectMapper objectMapper,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                 com.payflow.api.metrics.PayFlowMetricsService metricsService) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletRepository = walletRepository;
        this.paymentRepository = paymentRepository;
        this.reportRepository = reportRepository;
        this.fraudProofRepository = fraudProofRepository;
        this.allowanceRepository = allowanceRepository;
        this.offlineTransactionRepository = offlineTransactionRepository;
        this.protectedSettlementRepository = protectedSettlementRepository;
        this.merkleBatchRepository = merkleBatchRepository;
        this.merkleLeafRepository = merkleLeafRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    @Transactional
    public ReconciliationReportResponse runReconciliation() {
        log.info("Initiating system-wide financial reconciliation audit (set-based query execution)...");

        List<String> discrepancies = new ArrayList<>();

        // 1. Double-Entry Ledger Parity Audit (Global Sum of Debits == Global Sum of Credits)
        BigDecimal totalDebits = ledgerEntryRepository.sumAmountByEntryType(EntryType.DEBIT);
        BigDecimal totalCredits = ledgerEntryRepository.sumAmountByEntryType(EntryType.CREDIT);

        totalDebits = totalDebits != null ? totalDebits.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        totalCredits = totalCredits != null ? totalCredits.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        if (totalDebits.compareTo(totalCredits) != 0) {
            String parityDiscrepancy = String.format("Ledger Parity Failure: Total Debits (%s) != Total Credits (%s). Difference: %s",
                    totalDebits, totalCredits, totalDebits.subtract(totalCredits).abs());
            log.error(parityDiscrepancy);
            discrepancies.add(parityDiscrepancy);
        }

        // 2. Set-Based Wallet Balance Integrity Audit (AUD-08)
        long totalWalletsCount = walletRepository.count();
        List<Wallet> negativeWallets = walletRepository.findByBalanceLessThan(BigDecimal.ZERO);
        int mismatchedWalletsCount = negativeWallets.size();

        for (Wallet wallet : negativeWallets) {
            String negativeBalanceMsg = String.format("Wallet %s has illegal negative balance: %s", wallet.getId(), wallet.getBalance());
            log.error(negativeBalanceMsg);
            discrepancies.add(negativeBalanceMsg);
        }

        // 3. Set-Based Completed Payment Verification (AUD-08)
        List<UUID> invalidPayments = paymentRepository.findCompletedPaymentsWithInvalidLedgerCount();
        for (UUID paymentId : invalidPayments) {
            String paymentDiscrepancy = String.format("Payment %s has invalid ledger entry count (expected exactly 2: 1 DEBIT, 1 CREDIT)", paymentId);
            log.error(paymentDiscrepancy);
            discrepancies.add(paymentDiscrepancy);
        }

        // 4. Offline Fraud Proof & Revocation Integrity Audit
        if (fraudProofRepository != null && allowanceRepository != null && offlineTransactionRepository != null) {
            // Check A: No OPEN or CONFIRMED fraud proof refers to a non-revoked allowance
            List<FraudProof> activeProofs = fraudProofRepository.findAll().stream()
                    .filter(p -> p.getStatus() == FraudProofStatus.OPEN || p.getStatus() == FraudProofStatus.CONFIRMED)
                    .toList();

            for (FraudProof proof : activeProofs) {
                if (proof.getAllowance().getStatus() != AllowanceStatus.REVOKED) {
                    String discrepancy = String.format("Fraud Proof %s is %s but referenced allowance %s has non-revoked status: %s",
                            proof.getId(), proof.getStatus(), proof.getAllowance().getId(), proof.getAllowance().getStatus());
                    log.error(discrepancy);
                    discrepancies.add(discrepancy);
                }
            }

            // Check B: Every REVOKED allowance caused by EQUIVOCATION_FRAUD has a matching proof
            List<OfflineAllowance> fraudRevokedAllowances = allowanceRepository.findAll().stream()
                    .filter(a -> a.getStatus() == AllowanceStatus.REVOKED &&
                                 a.getRevocationReason() == com.payflow.core.domain.offline.RevocationReason.EQUIVOCATION_FRAUD)
                    .toList();
            for (OfflineAllowance a : fraudRevokedAllowances) {
                if (!fraudProofRepository.existsByAllowanceId(a.getId())) {
                    String discrepancy = String.format("Allowance %s is revoked for EQUIVOCATION_FRAUD but has no matching fraud proof record",
                            a.getId());
                    log.error(discrepancy);
                    discrepancies.add(discrepancy);
                }
            }

            // Check C: No allowance has spent > allocated
            List<OfflineAllowance> overspentAllowances = allowanceRepository.findAll().stream()
                    .filter(a -> a.getSpentAmount().compareTo(a.getAllocatedAmount()) > 0)
                    .toList();
            for (OfflineAllowance a : overspentAllowances) {
                String discrepancy = String.format("Allowance %s has spent amount (%s) exceeding allocated amount (%s)",
                        a.getId(), a.getSpentAmount(), a.getAllocatedAmount());
                log.error(discrepancy);
                discrepancies.add(discrepancy);
            }

            // Check D: Protected settlements balance (debits == credits, valid payment ledger)
            if (protectedSettlementRepository != null) {
                List<com.payflow.core.domain.offline.OfflineProtectedSettlement> protectedSettlements = protectedSettlementRepository.findAll();
                for (var ps : protectedSettlements) {
                    if (ps.getPayment() != null) {
                        List<LedgerEntry> entries = ledgerEntryRepository.findByPaymentId(ps.getPayment().getId());
                        if (entries.size() != 2) {
                            String discrepancy = String.format("Protected settlement %s has invalid ledger entry count: %d (expected 2)",
                                    ps.getTxId(), entries.size());
                            log.error(discrepancy);
                            discrepancies.add(discrepancy);
                        }
                    }
                }
            }
        }

        // 5. Cryptographic Merkle Ledger Batches & Chain Integrity Audit
        if (merkleBatchRepository != null && merkleLeafRepository != null) {
            List<LedgerMerkleBatch> recentBatches = merkleBatchRepository.findTop10ByOrderByBatchNumberDesc();
            for (LedgerMerkleBatch batch : recentBatches) {
                List<LedgerMerkleLeaf> leaves = merkleLeafRepository.findAllByBatchNumberOrderByLeafIndexAsc(batch.getBatchNumber());
                if (leaves.size() != batch.getLeafCount()) {
                    String discrepancy = String.format("Merkle batch %d leaf count mismatch: DB has %d, batch header has %d",
                            batch.getBatchNumber(), leaves.size(), batch.getLeafCount());
                    log.error(discrepancy);
                    discrepancies.add(discrepancy);
                    emitLedgerRootVerificationFailed(batch.getBatchNumber(), discrepancy);
                    continue;
                }

                List<String> hashes = leaves.stream().map(LedgerMerkleLeaf::getLeafHash).toList();
                MerkleTree tree = new MerkleTree(hashes);
                String recomputedRoot = tree.getRootHash();
                if (!recomputedRoot.equalsIgnoreCase(batch.getRootHash())) {
                    String discrepancy = String.format("Merkle batch %d root hash mismatch: header=%s, recomputed=%s",
                            batch.getBatchNumber(), batch.getRootHash(), recomputedRoot);
                    log.error(discrepancy);
                    discrepancies.add(discrepancy);
                    emitLedgerRootVerificationFailed(batch.getBatchNumber(), discrepancy);
                    continue;
                }

                String recomputedChain = CanonicalLedgerEntry.computeChainHash(
                        batch.getPreviousRootHash(), batch.getRootHash(), batch.getBatchNumber(), batch.getLeafCount()
                );
                if (!recomputedChain.equalsIgnoreCase(batch.getChainHash())) {
                    String discrepancy = String.format("Merkle batch %d chain hash mismatch: header=%s, recomputed=%s",
                            batch.getBatchNumber(), batch.getChainHash(), recomputedChain);
                    log.error(discrepancy);
                    discrepancies.add(discrepancy);
                    emitLedgerRootVerificationFailed(batch.getBatchNumber(), discrepancy);
                }

                if (batch.getBatchNumber() > 1L) {
                    Optional<LedgerMerkleBatch> prevOpt = merkleBatchRepository.findById(batch.getBatchNumber() - 1L);
                    if (prevOpt.isPresent() && !prevOpt.get().getRootHash().equalsIgnoreCase(batch.getPreviousRootHash())) {
                        String discrepancy = String.format("Merkle batch %d linkage broken: previousRootHash=%s != batch %d rootHash=%s",
                                batch.getBatchNumber(), batch.getPreviousRootHash(), prevOpt.get().getBatchNumber(), prevOpt.get().getRootHash());
                        log.error(discrepancy);
                        discrepancies.add(discrepancy);
                        emitLedgerRootVerificationFailed(batch.getBatchNumber(), discrepancy);
                    }
                }
            }
        }

        // 6. Determine Audit Status & Persist Non-Destructive Report
        if (metricsService != null && !discrepancies.isEmpty()) {
            metricsService.incrementReconciliationFailures(discrepancies.size());
        }
        ReconciliationStatus status = discrepancies.isEmpty() ? ReconciliationStatus.BALANCED : ReconciliationStatus.DISCREPANCY_DETECTED;

        String detailsJson = "[]";
        try {
            detailsJson = objectMapper.writeValueAsString(discrepancies);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize discrepancy details to JSON", e);
        }

        ReconciliationReport report = new ReconciliationReport(
                status,
                (int) totalWalletsCount,
                mismatchedWalletsCount,
                totalDebits,
                totalCredits,
                detailsJson
        );

        report = reportRepository.save(report);

        log.info("Completed financial reconciliation report: id={}, status={}, checkedWallets={}, discrepanciesCount={}",
                report.getId(), status, totalWalletsCount, discrepancies.size());

        return ReconciliationReportResponse.from(report);
    }

    @Transactional(readOnly = true)
    public Page<ReconciliationReportResponse> getReports(Pageable pageable) {
        Page<ReconciliationReport> reports = reportRepository.findAllByOrderByExecutedAtDesc(pageable);
        return reports.map(ReconciliationReportResponse::from);
    }

    private void emitLedgerRootVerificationFailed(Long batchNumber, String reason) {
        if (metricsService != null) {
            metricsService.incrementMerkleVerifyFailures();
        }
        if (outboxEventRepository != null) {
            try {
                Map<String, Object> payload = Map.of(
                        "batchNumber", batchNumber,
                        "reason", reason,
                        "detectedAt", java.time.Instant.now().toString()
                );
                OutboxEvent outboxEvent = new OutboxEvent(
                        "LEDGER_ROOT",
                        String.valueOf(batchNumber),
                        "LEDGER_ROOT_VERIFICATION_FAILED",
                        objectMapper.writeValueAsString(payload)
                );
                outboxEventRepository.save(outboxEvent);
            } catch (Exception ex) {
                log.error("Failed to emit LEDGER_ROOT_VERIFICATION_FAILED outbox event for batch {}", batchNumber, ex);
            }
        }
    }
}
