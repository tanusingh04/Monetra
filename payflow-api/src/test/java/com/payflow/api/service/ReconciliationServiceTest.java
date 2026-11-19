package com.payflow.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.reconciliation.ReconciliationReportResponse;
import com.payflow.core.domain.ledger.EntryType;
import com.payflow.core.domain.offline.AllowanceStatus;
import com.payflow.core.domain.offline.FraudProof;
import com.payflow.core.domain.offline.FraudProofStatus;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.reconciliation.ReconciliationReport;
import com.payflow.core.domain.reconciliation.ReconciliationStatus;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ReconciliationReportRepository reportRepository;

    @Mock
    private FraudProofRepository fraudProofRepository;

    @Mock
    private OfflineAllowanceRepository allowanceRepository;

    @Mock
    private OfflineTransactionRepository offlineTransactionRepository;

    @Mock
    private LedgerMerkleBatchRepository merkleBatchRepository;

    @Mock
    private LedgerMerkleLeafRepository merkleLeafRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
    }

    @Test
    @DisplayName("Should produce BALANCED report when ledger debits equal credits and payments match")
    void runReconciliation_Balanced() {
        when(ledgerEntryRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(BigDecimal.valueOf(3000));
        when(ledgerEntryRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(BigDecimal.valueOf(3000));

        when(walletRepository.count()).thenReturn(2L);
        when(walletRepository.findByBalanceLessThan(BigDecimal.ZERO)).thenReturn(List.of());
        when(paymentRepository.findCompletedPaymentsWithInvalidLedgerCount()).thenReturn(List.of());

        when(reportRepository.save(any(ReconciliationReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReconciliationReportResponse report = reconciliationService.runReconciliation();

        assertThat(report.status()).isEqualTo(ReconciliationStatus.BALANCED.name());
        assertThat(report.totalLedgerDebits()).isEqualByComparingTo(BigDecimal.valueOf(3000));
        assertThat(report.totalLedgerCredits()).isEqualByComparingTo(BigDecimal.valueOf(3000));
        assertThat(report.netLedgerParityDifference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.mismatchedWalletsCount()).isEqualTo(0);

        verify(reportRepository).save(any(ReconciliationReport.class));
    }

    @Test
    @DisplayName("Should detect DISCREPANCY when ledger sum of debits does not equal credits")
    void runReconciliation_LedgerMismatch_Detected() {
        // System has ₹3,000 debits but only ₹2,000 credits (a ₹1,000 imbalance)
        when(ledgerEntryRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(BigDecimal.valueOf(3000));
        when(ledgerEntryRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(BigDecimal.valueOf(2000));

        when(walletRepository.count()).thenReturn(2L);
        when(walletRepository.findByBalanceLessThan(BigDecimal.ZERO)).thenReturn(List.of());
        when(paymentRepository.findCompletedPaymentsWithInvalidLedgerCount()).thenReturn(List.of());

        when(reportRepository.save(any(ReconciliationReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReconciliationReportResponse report = reconciliationService.runReconciliation();

        assertThat(report.status()).isEqualTo(ReconciliationStatus.DISCREPANCY_DETECTED.name());
        assertThat(report.netLedgerParityDifference()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(report.discrepancyDetails()).contains("Ledger Parity Failure");
    }

    @Test
    @DisplayName("Should detect DISCREPANCY when a completed payment has missing or mismatched ledger entries")
    void runReconciliation_PaymentMissingEntry_Detected() {
        when(ledgerEntryRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(BigDecimal.valueOf(3000));
        when(ledgerEntryRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(BigDecimal.valueOf(3000));

        when(walletRepository.count()).thenReturn(2L);
        when(walletRepository.findByBalanceLessThan(BigDecimal.ZERO)).thenReturn(List.of());
        when(paymentRepository.findCompletedPaymentsWithInvalidLedgerCount()).thenReturn(List.of(UUID.randomUUID()));

        when(reportRepository.save(any(ReconciliationReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReconciliationReportResponse report = reconciliationService.runReconciliation();

        assertThat(report.status()).isEqualTo(ReconciliationStatus.DISCREPANCY_DETECTED.name());
        assertThat(report.discrepancyDetails()).contains("invalid ledger entry count");
    }

    @Test
    @DisplayName("Should detect DISCREPANCY when an open fraud proof refers to a non-revoked allowance")
    void runReconciliation_FraudProofDiscrepancy_Detected() {
        when(ledgerEntryRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(BigDecimal.valueOf(3000));
        when(ledgerEntryRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(BigDecimal.valueOf(3000));

        when(walletRepository.count()).thenReturn(2L);
        when(walletRepository.findByBalanceLessThan(BigDecimal.ZERO)).thenReturn(List.of());
        when(paymentRepository.findCompletedPaymentsWithInvalidLedgerCount()).thenReturn(List.of());

        User user = new User("u1", "u1@test.com", "hash", UserRole.ROLE_USER);
        Wallet wallet = new Wallet(user, "INR", BigDecimal.valueOf(1000));
        OfflineAllowance allowance = new OfflineAllowance(user, wallet, "dev1", BigDecimal.valueOf(100), BigDecimal.valueOf(50), java.time.Instant.now().plusSeconds(3600));
        // Status is ACTIVE, not REVOKED!

        FraudProof proof = new FraudProof(allowance, "dev1", wallet, 1L, UUID.randomUUID(), UUID.randomUUID(), "p1", "s1", "p2", "s2");
        when(fraudProofRepository.findAll()).thenReturn(List.of(proof));

        when(reportRepository.save(any(ReconciliationReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReconciliationReportResponse report = reconciliationService.runReconciliation();

        assertThat(report.status()).isEqualTo(ReconciliationStatus.DISCREPANCY_DETECTED.name());
        assertThat(report.discrepancyDetails()).contains("has non-revoked status");
    }

    @Test
    @DisplayName("Should detect DISCREPANCY and emit alert when Merkle batch root hash is tampered")
    void runReconciliation_MerkleRootMismatch_Detected() {
        when(ledgerEntryRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(BigDecimal.valueOf(3000));
        when(ledgerEntryRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(BigDecimal.valueOf(3000));

        when(walletRepository.count()).thenReturn(2L);
        when(walletRepository.findByBalanceLessThan(BigDecimal.ZERO)).thenReturn(List.of());
        when(paymentRepository.findCompletedPaymentsWithInvalidLedgerCount()).thenReturn(List.of());

        // Merkle batch with mismatched root
        com.payflow.core.domain.merkle.LedgerMerkleBatch tamperedBatch = new com.payflow.core.domain.merkle.LedgerMerkleBatch(
                1L, 1, "0000000000000000000000000000000000000000000000000000000000000001",
                com.payflow.core.merkle.CanonicalLedgerEntry.GENESIS_PREVIOUS_ROOT,
                "0000000000000000000000000000000000000000000000000000000000000002",
                java.time.Instant.now(), java.time.Instant.now()
        );
        com.payflow.core.domain.merkle.LedgerMerkleLeaf leaf = new com.payflow.core.domain.merkle.LedgerMerkleLeaf(
                UUID.randomUUID(), 1L, 0, "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        );

        when(merkleBatchRepository.findTop10ByOrderByBatchNumberDesc()).thenReturn(List.of(tamperedBatch));
        when(merkleLeafRepository.findAllByBatchNumberOrderByLeafIndexAsc(1L)).thenReturn(List.of(leaf));

        when(reportRepository.save(any(ReconciliationReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReconciliationReportResponse report = reconciliationService.runReconciliation();

        assertThat(report.status()).isEqualTo(ReconciliationStatus.DISCREPANCY_DETECTED.name());
        assertThat(report.discrepancyDetails()).contains("root hash mismatch");
        verify(outboxEventRepository, atLeastOnce()).save(any(com.payflow.core.domain.outbox.OutboxEvent.class));
    }
}
