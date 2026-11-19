package com.payflow.api.service.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.offline.AllocateAllowanceRequest;
import com.payflow.api.dto.offline.AllowanceCertificateResponse;
import com.payflow.api.dto.offline.EnrollDeviceRequest;
import com.payflow.api.dto.offline.OfflineReceiptDto;
import com.payflow.api.dto.offline.OfflineSyncRequest;
import com.payflow.api.dto.offline.OfflineSyncResponse;
import com.payflow.api.dto.reconciliation.ReconciliationReportResponse;
import com.payflow.api.service.ReconciliationService;
import com.payflow.core.domain.ledger.EntryType;
import com.payflow.core.domain.offline.*;
import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.domain.reconciliation.ReconciliationStatus;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
public class ReceiverProtectedSettlementIntegrationTest {

    @Autowired
    private DeviceEnrollmentService deviceEnrollmentService;

    @Autowired
    private OfflineAllowanceService allowanceService;

    @Autowired
    private OfflineSettlementService settlementService;

    @Autowired
    private OfflineCryptoService cryptoService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private OfflineAllowanceRepository allowanceRepository;

    @Autowired
    private OfflineTransactionRepository offlineTransactionRepository;

    @Autowired
    private OfflineProtectedSettlementRepository protectedSettlementRepository;

    @Autowired
    private OfflineRejectedReceiptRepository rejectedReceiptRepository;

    @Autowired
    private DeviceEnrollmentRepository deviceEnrollmentRepository;

    @Autowired
    private FraudProofRepository fraudProofRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ReputationEventRepository reputationEventRepository;

    @Autowired
    private UserReputationRepository userReputationRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User senderUser;
    private User receiverUser;
    private Wallet senderWallet;
    private Wallet receiverWallet;

    private KeyPair deviceKeyPair;
    private String devicePublicKeyBase64;
    private final String deviceId = "protected-device-01";
    private AllowanceCertificateResponse allowanceCert;

    @BeforeEach
    void setUp() throws Exception {
        try {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_immutable_fraud_proofs");
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_immutable_reputation_events");
        } catch (Exception ignored) {}

        protectedSettlementRepository.deleteAll();
        rejectedReceiptRepository.deleteAll();
        fraudProofRepository.deleteAll();
        offlineTransactionRepository.deleteAll();
        allowanceRepository.deleteAll();
        deviceEnrollmentRepository.deleteAll();
        outboxEventRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        paymentRepository.deleteAll();
        walletRepository.deleteAll();
        reputationEventRepository.deleteAll();
        userReputationRepository.deleteAll();
        notificationRepository.deleteAll();
        userRepository.deleteAll();

        // 1. Users & Wallets
        senderUser = userRepository.save(new User("prot_sender", "sender@prot.test", "hash", UserRole.ROLE_USER));
        receiverUser = userRepository.save(new User("prot_receiver", "receiver@prot.test", "hash", UserRole.ROLE_USER));

        senderWallet = walletRepository.save(new Wallet(senderUser, "INR", new BigDecimal("5000.0000")));
        receiverWallet = walletRepository.save(new Wallet(receiverUser, "INR", new BigDecimal("1000.0000")));

        // 2. Device KeyPair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        deviceKeyPair = keyGen.generateKeyPair();
        devicePublicKeyBase64 = Base64.getEncoder().encodeToString(deviceKeyPair.getPublic().getEncoded());

        // 3. Enroll Device
        deviceEnrollmentService.enrollDevice(new EnrollDeviceRequest(deviceId, "Pixel 8 Pro", devicePublicKeyBase64), senderUser.getId());

        // 4. Allocate ₹2,000 allowance (max single ₹1,000)
        allowanceCert = allowanceService.allocateAllowance(
                new AllocateAllowanceRequest(senderWallet.getId(), deviceId, new BigDecimal("2000.0000"), new BigDecimal("1000.0000"), 24),
                senderUser.getId()
        );

        // 5. Register H2 trigger
        try {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_immutable_fraud_proofs");
            jdbcTemplate.execute("CREATE TRIGGER trg_immutable_fraud_proofs BEFORE UPDATE, DELETE ON offline_fraud_proofs FOR EACH ROW CALL \"com.payflow.api.service.offline.H2FraudProofImmutabilityTrigger\"");
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("1c: Covered second receipt settles from reserve + proof created + allowance & device revoked")
    void testCoveredSecondReceiptSettlesAndProofRevocation() {
        // Step 1: Settle Receipt 1 (Counter 1L, ₹400)
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("400.0000"), 1L, "nonce-001");
        OfflineSyncResponse response1 = settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt1)), receiverUser.getId());

        assertThat(response1.totalSettled()).isEqualTo(1);
        assertThat(response1.results().get(0).status()).isEqualTo("SETTLED");

        // Step 2: Submit conflicting Receipt 2 (Same counter 1L, ₹300, different txId & nonce)
        // Amount ₹300 <= maxSingle (1000), remaining (1600), sender reserve (1600) -> COVERED!
        UUID txId2 = UUID.randomUUID();
        OfflineReceiptDto receipt2 = createSignedReceipt(txId2, new BigDecimal("300.0000"), 1L, "nonce-002");
        OfflineSyncResponse response2 = settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt2)), receiverUser.getId());

        assertThat(response2.totalSettled()).isEqualTo(1);
        assertThat(response2.results().get(0).status()).isEqualTo("SETTLED");
        assertThat(response2.results().get(0).message()).contains("receiver protection");

        // Verify FraudProof
        List<FraudProof> proofs = fraudProofRepository.findAll();
        assertThat(proofs).hasSize(1);
        FraudProof proof = proofs.get(0);
        assertThat(proof.getSecondReceiptSettled()).isTrue();
        assertThat(proof.getProtectedAmount()).isEqualByComparingTo(new BigDecimal("300.0000"));
        assertThat(proof.getUncoveredAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(proof.getSettlementPaymentId()).isNotNull();

        // Verify Protected Settlement record
        List<OfflineProtectedSettlement> protectedList = protectedSettlementRepository.findAll();
        assertThat(protectedList).hasSize(1);
        assertThat(protectedList.get(0).getTxId()).isEqualTo(txId2);
        assertThat(protectedList.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("300.0000"));

        // Verify Revocations with EQUIVOCATION_FRAUD reason
        OfflineAllowance allowance = allowanceRepository.findById(allowanceCert.allowanceId()).orElseThrow();
        assertThat(allowance.getStatus()).isEqualTo(AllowanceStatus.REVOKED);
        assertThat(allowance.getRevocationReason()).isEqualTo(RevocationReason.EQUIVOCATION_FRAUD);

        DeviceEnrollment device = deviceEnrollmentRepository.findByUserIdAndDeviceId(senderUser.getId(), deviceId).orElseThrow();
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.REVOKED);
        assertThat(device.getRevocationReason()).isEqualTo(RevocationReason.EQUIVOCATION_FRAUD);

        // Verify Balances:
        // Sender: 5000 - 400 - 300 = 4300 balance, reserved = 2000 - 400 - 300 = 1300, available = 3000
        Wallet senderPost = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertEquals(new BigDecimal("4300.0000"), senderPost.getBalance());
        assertEquals(new BigDecimal("1300.0000"), senderPost.getReservedBalance());
        assertEquals(new BigDecimal("3000.0000"), senderPost.getAvailableBalance());

        // Receiver: 1000 + 400 + 300 = 1700 balance
        Wallet receiverPost = walletRepository.findById(receiverWallet.getId()).orElseThrow();
        assertEquals(new BigDecimal("1700.0000"), receiverPost.getBalance());

        // Verify Outbox Events (OFFLINE_FRAUD_PROOF and OFFLINE_RECEIPT_PROTECTED)
        List<OutboxEvent> outbox = outboxEventRepository.findAll();
        boolean hasProtectedEvent = outbox.stream().anyMatch(e -> "OFFLINE_RECEIPT_PROTECTED".equals(e.getAggregateType()));
        boolean hasFraudEvent = outbox.stream().anyMatch(e -> "OFFLINE_FRAUD_PROOF".equals(e.getAggregateType()));
        assertThat(hasProtectedEvent).isTrue();
        assertThat(hasFraudEvent).isTrue();

        // Verify Double-Entry Ledger Parity: Debits == Credits == 700.0000
        BigDecimal totalDebits = ledgerEntryRepository.sumAmountByEntryType(EntryType.DEBIT);
        BigDecimal totalCredits = ledgerEntryRepository.sumAmountByEntryType(EntryType.CREDIT);
        assertThat(totalDebits).isEqualByComparingTo(new BigDecimal("700.0000"));
        assertThat(totalCredits).isEqualByComparingTo(new BigDecimal("700.0000"));

        // Reconciliation passes
        ReconciliationReportResponse recon = reconciliationService.runReconciliation();
        assertThat(recon.status()).isEqualTo(ReconciliationStatus.BALANCED.name());
    }

    @Test
    @DisplayName("1c: Uncovered second receipt rejected with uncovered_amount recorded + proof created")
    void testUncoveredSecondReceiptRejectedWithUncoveredAmount() {
        // Settle Receipt 1 (₹800, counter 1)
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("800.0000"), 1L, "nonce-1");
        settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt1)), receiverUser.getId());

        // Submit conflicting Receipt 2 (₹1,500, counter 1L)
        // Amount exceeds single tx limit of 1000 and remaining allowance (1200) -> UNCOVERED!
        UUID txId2 = UUID.randomUUID();
        OfflineReceiptDto receipt2 = createSignedReceipt(txId2, new BigDecimal("1500.0000"), 1L, "nonce-uncovered");
        OfflineSyncResponse response = settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt2)), receiverUser.getId());

        assertThat(response.totalRejected()).isEqualTo(1);
        assertThat(response.results().get(0).message()).contains("FRAUD_PROOF_DETECTED:");
        assertThat(response.results().get(0).message()).contains("UNCOVERED_BY_RESERVE");

        // Verify Proof has uncovered outcome
        FraudProof proof = fraudProofRepository.findAll().get(0);
        assertThat(proof.getSecondReceiptSettled()).isFalse();
        assertThat(proof.getProtectedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(proof.getUncoveredAmount()).isEqualByComparingTo(new BigDecimal("1500.0000"));

        // Verify Rejected Receipt persisted in offline_rejected_receipts
        List<OfflineRejectedReceipt> rejectedList = rejectedReceiptRepository.findAll();
        assertThat(rejectedList).hasSize(1);
        assertThat(rejectedList.get(0).getTxId()).isEqualTo(txId2);
        assertThat(rejectedList.get(0).getRejectionReason()).contains("UNCOVERED_BY_RESERVE");

        // Outbox event: OFFLINE_RECEIPT_UNCOVERED
        boolean hasUncoveredEvent = outboxEventRepository.findAll().stream()
                .anyMatch(e -> "OFFLINE_RECEIPT_UNCOVERED".equals(e.getAggregateType()));
        assertThat(hasUncoveredEvent).isTrue();
    }

    @Test
    @DisplayName("1d: Grace settlement for honest receiver on EQUIVOCATION_FRAUD revocation")
    void testGraceSettlementForHonestReceiverOnEquivocationRevocation() {
        // Step 1: Revoke allowance due to EQUIVOCATION_FRAUD (with ₹1,000 remaining reserve)
        OfflineAllowance allowance = allowanceRepository.findById(allowanceCert.allowanceId()).orElseThrow();
        allowance.recordSpent(new BigDecimal("1000.0000"), 1L);
        allowance.markRevoked(RevocationReason.EQUIVOCATION_FRAUD);
        allowanceRepository.save(allowance);

        Wallet senderW = walletRepository.findById(senderWallet.getId()).orElseThrow();
        senderW.settleReservedDebit(new BigDecimal("1000.0000")); // reserved is now 1000
        walletRepository.save(senderW);

        // Record settled tx for counter 1
        OfflineTransaction tx1 = new OfflineTransaction(
                UUID.randomUUID(), allowance, senderW, receiverWallet,
                new BigDecimal("1000.0000"), "INR", 1L, "nonce-1", "sig-1", "p1", Instant.now());
        offlineTransactionRepository.save(tx1);

        // Step 2: Honest merchant submits Receipt with fresh counter 2L (₹400 <= remaining reserve ₹1,000)
        UUID txIdGrace = UUID.randomUUID();
        OfflineReceiptDto graceReceipt = createSignedReceipt(txIdGrace, new BigDecimal("400.0000"), 2L, "nonce-grace");
        OfflineSyncResponse response = settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(graceReceipt)), receiverUser.getId());

        assertThat(response.totalSettled()).isEqualTo(1);
        assertThat(response.results().get(0).status()).isEqualTo("SETTLED");
        assertThat(response.results().get(0).message()).contains("grace period");

        // Sender reserved balance reduced from 1000 to 600
        Wallet senderPost = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertEquals(new BigDecimal("600.0000"), senderPost.getReservedBalance());

        // Receiver balance increased by 400
        Wallet receiverPost = walletRepository.findById(receiverWallet.getId()).orElseThrow();
        assertEquals(new BigDecimal("1400.0000"), receiverPost.getBalance());
    }

    @Test
    @DisplayName("1d: Rejection on ADMIN or DEVICE_LOST revocation (no grace settlement for stolen device)")
    void testRejectionOnAdminOrLostDeviceRevocation() {
        OfflineAllowance allowance = allowanceRepository.findById(allowanceCert.allowanceId()).orElseThrow();
        allowance.markRevoked(RevocationReason.DEVICE_LOST);
        allowanceRepository.save(allowance);

        DeviceEnrollment device = deviceEnrollmentRepository.findByUserIdAndDeviceId(senderUser.getId(), deviceId).orElseThrow();
        device.revoke(RevocationReason.DEVICE_LOST);
        deviceEnrollmentRepository.save(device);

        UUID txId = UUID.randomUUID();
        OfflineReceiptDto receipt = createSignedReceipt(txId, new BigDecimal("200.0000"), 1L, "nonce-lost");
        OfflineSyncResponse response = settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt)), receiverUser.getId());

        assertThat(response.totalRejected()).isEqualTo(1);
        assertThat(response.results().get(0).message()).contains("Device reported lost or stolen");

        // No funds moved
        Wallet sender = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertEquals(new BigDecimal("2000.0000"), sender.getReservedBalance());
    }

    @Test
    @DisplayName("1a: Batch transaction isolation - one bad receipt does not roll back valid settlements")
    void testBatchIsolationOneFailingReceiptDoesNotRollbackOthers() {
        // Receipt 1: Valid ₹100, counter 1
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("100.0000"), 1L, "nonce-b1");

        // Receipt 2: Invalid signature, counter 2
        UUID txId2 = UUID.randomUUID();
        OfflineReceiptDto receipt2 = new OfflineReceiptDto(
                txId2, allowanceCert.allowanceId(), senderWallet.getId(), receiverWallet.getId(),
                new BigDecimal("200.0000"), "INR", 2L, "nonce-b2",
                Base64.getEncoder().encodeToString(new byte[64]), Instant.now()
        );

        // Receipt 3: Valid ₹300, counter 3
        UUID txId3 = UUID.randomUUID();
        OfflineReceiptDto receipt3 = createSignedReceipt(txId3, new BigDecimal("300.0000"), 3L, "nonce-b3");

        OfflineSyncResponse response = settlementService.settleOfflineBatch(
                new OfflineSyncRequest(List.of(receipt1, receipt2, receipt3)), receiverUser.getId());

        assertThat(response.totalSubmitted()).isEqualTo(3);
        assertThat(response.totalSettled()).isEqualTo(2);
        assertThat(response.totalRejected()).isEqualTo(1);

        // Verify receipt 1 and 3 are settled and committed in DB
        assertThat(offlineTransactionRepository.existsById(txId1)).isTrue();
        assertThat(offlineTransactionRepository.existsById(txId3)).isTrue();

        // Total debits = total credits = 400.0000
        BigDecimal totalDebits = ledgerEntryRepository.sumAmountByEntryType(EntryType.DEBIT);
        BigDecimal totalCredits = ledgerEntryRepository.sumAmountByEntryType(EntryType.CREDIT);
        assertThat(totalDebits).isEqualByComparingTo(new BigDecimal("400.0000"));
        assertThat(totalCredits).isEqualByComparingTo(new BigDecimal("400.0000"));
    }

    @Test
    @DisplayName("1c: Concurrent conflicting receipts produce exactly one proof and no double payment")
    void testConcurrentConflictingReceiptsSingleProofNoDoubleSettlement() throws Exception {
        // Settle Receipt 1 (₹200, counter 1)
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("200.0000"), 1L, "nonce-c-base");
        settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt1)), receiverUser.getId());

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<OfflineReceiptDto> conflictingReceipts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID txId = UUID.randomUUID();
            conflictingReceipts.add(createSignedReceipt(txId, new BigDecimal(100 + i * 20 + ".0000"), 1L, "nonce-c-" + i));
        }

        AtomicInteger settledCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    OfflineSyncResponse resp = settlementService.settleOfflineBatch(
                            new OfflineSyncRequest(List.of(conflictingReceipts.get(index))), receiverUser.getId());
                    settledCount.addAndGet(resp.totalSettled());
                    rejectedCount.addAndGet(resp.totalRejected());
                } catch (Exception ignored) {}
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Exactly one fraud proof created for counter 1
        List<FraudProof> proofs = fraudProofRepository.findByAllowanceId(allowanceCert.allowanceId());
        assertThat(proofs).hasSize(1);

        // At most 1 protected settlement created
        List<OfflineProtectedSettlement> protectedList = protectedSettlementRepository.findAll();
        assertThat(protectedList.size()).isLessThanOrEqualTo(1);

        // Wallet balances must remain non-negative and consistent
        Wallet sender = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertThat(sender.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(sender.getReservedBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Ledger parity must hold
        BigDecimal totalDebits = ledgerEntryRepository.sumAmountByEntryType(EntryType.DEBIT);
        BigDecimal totalCredits = ledgerEntryRepository.sumAmountByEntryType(EntryType.CREDIT);
        assertThat(totalDebits).isEqualByComparingTo(totalCredits);
    }

    private OfflineReceiptDto createSignedReceipt(UUID txId, BigDecimal amount, long counter, String nonce) {
        String payload = cryptoService.buildTransactionPayload(
                txId, allowanceCert.allowanceId(), senderWallet.getId(), receiverWallet.getId(),
                amount, "INR", counter, nonce
        );
        String signature = cryptoService.signData(payload.getBytes(StandardCharsets.UTF_8), deviceKeyPair.getPrivate());
        return new OfflineReceiptDto(
                txId,
                allowanceCert.allowanceId(),
                senderWallet.getId(),
                receiverWallet.getId(),
                amount,
                "INR",
                counter,
                nonce,
                signature,
                Instant.now()
        );
    }
}
