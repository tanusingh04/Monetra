package com.payflow.api.service.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.offline.*;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class OfflineFraudProofIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
    private DeviceEnrollmentRepository deviceEnrollmentRepository;

    @Autowired
    private FraudProofRepository fraudProofRepository;

    @Autowired
    private OfflineProtectedSettlementRepository protectedSettlementRepository;

    @Autowired
    private OfflineRejectedReceiptRepository rejectedReceiptRepository;

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

    @Autowired
    private ObjectMapper objectMapper;

    private User senderUser;
    private User receiverUser;
    private Wallet senderWallet;
    private Wallet receiverWallet;

    private KeyPair deviceKeyPair;
    private String devicePublicKeyBase64;
    private final String deviceId = "pixel-8-pro-fraud-test";
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

        // 1. Create Users & Wallets
        senderUser = userRepository.save(new User("fraud_sender", "sender@fraud.test", "hash", UserRole.ROLE_USER));
        receiverUser = userRepository.save(new User("fraud_receiver", "receiver@fraud.test", "hash", UserRole.ROLE_USER));

        senderWallet = walletRepository.save(new Wallet(senderUser, "INR", new BigDecimal("5000.0000")));
        receiverWallet = walletRepository.save(new Wallet(receiverUser, "INR", new BigDecimal("1000.0000")));

        // 2. Generate Device KeyPair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        deviceKeyPair = keyGen.generateKeyPair();
        devicePublicKeyBase64 = Base64.getEncoder().encodeToString(deviceKeyPair.getPublic().getEncoded());

        // 3. Enroll Device
        EnrollDeviceRequest enrollRequest = new EnrollDeviceRequest(deviceId, "Pixel 8 Pro", devicePublicKeyBase64);
        deviceEnrollmentService.enrollDevice(enrollRequest, senderUser.getId());

        // 4. Allocate ₹2,000 Offline Allowance
        AllocateAllowanceRequest allocRequest = new AllocateAllowanceRequest(
                senderWallet.getId(), deviceId, new BigDecimal("2000.0000"), new BigDecimal("1000.0000"), 24
        );
        allowanceCert = allowanceService.allocateAllowance(allocRequest, senderUser.getId());

        // 5. Register Immutability Trigger for H2 test environment (PostgreSQL uses V5 trigger)
        try {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_immutable_fraud_proofs");
            jdbcTemplate.execute("CREATE TRIGGER trg_immutable_fraud_proofs BEFORE UPDATE, DELETE ON offline_fraud_proofs FOR EACH ROW CALL \"com.payflow.api.service.offline.H2FraudProofImmutabilityTrigger\"");
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(OfflineFraudProofIntegrationTest.class).warn("Trigger creation notice: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("Full Flow: Equivocation detection -> FraudProof stored, device & allowance revoked, outbox event written, ledger balanced, reconciliation passes")
    void testEquivocationFullFlow() {
        // Step 1: Settle Receipt 1 (Counter 1L, ₹400)
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("400.0000"), 1L, "nonce-001");
        OfflineSyncResponse response1 = settlementService.settleOfflineBatch(
                new OfflineSyncRequest(List.of(receipt1)), receiverUser.getId());

        assertThat(response1.totalSettled()).isEqualTo(1);
        assertThat(response1.results().get(0).status()).isEqualTo("SETTLED");

        // Verify intermediate wallet balance
        Wallet senderMid = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertEquals(new BigDecimal("4600.0000"), senderMid.getBalance());
        assertEquals(new BigDecimal("1600.0000"), senderMid.getReservedBalance());

        // Step 2: Submit conflicting Receipt 2 (exceeding single limit, uncovered equivocation)
        UUID txId2 = UUID.randomUUID();
        OfflineReceiptDto receipt2 = createSignedReceipt(txId2, new BigDecimal("1200.0000"), 1L, "nonce-002");
        OfflineSyncResponse response2 = settlementService.settleOfflineBatch(
                new OfflineSyncRequest(List.of(receipt2)), receiverUser.getId());

        assertThat(response2.totalRejected()).isEqualTo(1);
        String rejectionMsg = response2.results().get(0).message();
        assertThat(rejectionMsg).startsWith("FRAUD_PROOF_DETECTED:");

        String proofIdStr = rejectionMsg.substring("FRAUD_PROOF_DETECTED:".length());
        if (proofIdStr.contains(":")) {
            proofIdStr = proofIdStr.substring(0, proofIdStr.indexOf(":"));
        }
        UUID proofId = UUID.fromString(proofIdStr);

        // Step 3: Verify FraudProof entity
        FraudProof proof = fraudProofRepository.findById(proofId).orElseThrow();
        assertThat(proof.getAllowance().getId()).isEqualTo(allowanceCert.allowanceId());
        assertThat(proof.getDeviceId()).isEqualTo(deviceId);
        assertThat(proof.getCounter()).isEqualTo(1L);
        assertThat(proof.getFirstTxId()).isEqualTo(txId1);
        assertThat(proof.getSecondTxId()).isEqualTo(txId2);
        assertThat(proof.getStatus()).isEqualTo(FraudProofStatus.OPEN);

        // Step 4: Verify Allowance and Device are REVOKED
        OfflineAllowance revokedAllowance = allowanceRepository.findById(allowanceCert.allowanceId()).orElseThrow();
        assertThat(revokedAllowance.getStatus()).isEqualTo(AllowanceStatus.REVOKED);

        DeviceEnrollment revokedDevice = deviceEnrollmentRepository.findByUserIdAndDeviceId(senderUser.getId(), deviceId).orElseThrow();
        assertThat(revokedDevice.getStatus()).isEqualTo(DeviceStatus.REVOKED);

        // Step 5: Verify Reserved Balance Policy (Unspent reserved funds remain reserved, NOT released automatically)
        Wallet senderPostFraud = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertEquals(new BigDecimal("4600.0000"), senderPostFraud.getBalance());
        assertEquals(new BigDecimal("1600.0000"), senderPostFraud.getReservedBalance());
        assertEquals(new BigDecimal("3000.0000"), senderPostFraud.getAvailableBalance());

        // Step 6: Verify Outbox Event created
        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        boolean hasFraudEvent = outboxEvents.stream().anyMatch(e ->
                "OFFLINE_FRAUD_PROOF".equals(e.getAggregateType()) &&
                "OFFLINE_FRAUD_PROOF_DETECTED".equals(e.getEventType()) &&
                proofId.toString().equals(e.getAggregateId())
        );
        assertThat(hasFraudEvent).isTrue();

        // Step 7: Verify Double-Entry Ledger Invariants
        BigDecimal totalDebits = ledgerEntryRepository.sumAmountByEntryType(EntryType.DEBIT);
        BigDecimal totalCredits = ledgerEntryRepository.sumAmountByEntryType(EntryType.CREDIT);
        assertThat(totalDebits).isEqualByComparingTo(new BigDecimal("400.0000"));
        assertThat(totalCredits).isEqualByComparingTo(new BigDecimal("400.0000"));
        assertThat(totalDebits).isEqualByComparingTo(totalCredits);

        // Step 8: Verify System Reconciliation passes
        ReconciliationReportResponse recon = reconciliationService.runReconciliation();
        assertThat(recon.status()).isEqualTo(ReconciliationStatus.BALANCED.name());
        assertThat(recon.mismatchedWalletsCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Anti-Framing Test: Conflicting receipt with forged/invalid signature creates NO proof and does not revoke device")
    void testAntiFramingForgedSecondReceipt() {
        // Settle Receipt 1 (Counter 1L, ₹500)
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("500.0000"), 1L, "nonce-001");
        settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt1)), receiverUser.getId());

        // Conflicting receipt with fake signature (attempt to frame the sender)
        UUID txId2 = UUID.randomUUID();
        OfflineReceiptDto forgedReceipt = new OfflineReceiptDto(
                txId2, allowanceCert.allowanceId(), senderWallet.getId(), receiverWallet.getId(),
                new BigDecimal("250.0000"), "INR", 1L, "nonce-framing",
                Base64.getEncoder().encodeToString(new byte[64]), Instant.now()
        );

        OfflineSyncResponse response = settlementService.settleOfflineBatch(
                new OfflineSyncRequest(List.of(forgedReceipt)), receiverUser.getId());

        assertThat(response.totalRejected()).isEqualTo(1);
        assertThat(response.results().get(0).message()).contains("Invalid cryptographic device signature");

        // NO fraud proof must be created!
        assertThat(fraudProofRepository.count()).isEqualTo(0);

        // Device and Allowance must remain ACTIVE!
        OfflineAllowance allowance = allowanceRepository.findById(allowanceCert.allowanceId()).orElseThrow();
        assertThat(allowance.getStatus()).isEqualTo(AllowanceStatus.ACTIVE);

        DeviceEnrollment device = deviceEnrollmentRepository.findByUserIdAndDeviceId(senderUser.getId(), deviceId).orElseThrow();
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
    }

    @Test
    @DisplayName("Idempotency: Re-submitting identical receipt returns ALREADY_SETTLED and creates NO proof")
    void testIdenticalReceiptResubmission() {
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("300.0000"), 1L, "nonce-idempotent");
        settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt1)), receiverUser.getId());

        // Re-submit identical receipt
        OfflineSyncResponse response = settlementService.settleOfflineBatch(
                new OfflineSyncRequest(List.of(receipt1)), receiverUser.getId());

        assertThat(response.totalSettled()).isEqualTo(1);
        assertThat(response.results().get(0).status()).isEqualTo("ALREADY_SETTLED");
        assertThat(fraudProofRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("Concurrency: Parallel conflicting receipts produce exactly one proof without deadlocks or negative balance")
    void testConcurrentEquivocationHandling() throws Exception {
        // Settle Receipt 1
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("200.0000"), 1L, "nonce-base");
        settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt1)), receiverUser.getId());

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger rejectedCount = new AtomicInteger(0);

        // Prepare 4 conflicting receipts with counter 1L but different amounts
        List<OfflineReceiptDto> conflictingReceipts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID txId = UUID.randomUUID();
            conflictingReceipts.add(createSignedReceipt(txId, new BigDecimal(100 + i * 10 + ".0000"), 1L, "nonce-concurrent-" + i));
        }

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    OfflineSyncResponse resp = settlementService.settleOfflineBatch(
                            new OfflineSyncRequest(List.of(conflictingReceipts.get(index))), receiverUser.getId());
                    if (resp.totalRejected() > 0) {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Exactly one fraud proof should exist for this counter
        List<FraudProof> proofs = fraudProofRepository.findByAllowanceId(allowanceCert.allowanceId());
        assertThat(proofs).hasSize(1);

        // Check allowance revoked and balance consistent
        OfflineAllowance allowance = allowanceRepository.findById(allowanceCert.allowanceId()).orElseThrow();
        assertThat(allowance.getStatus()).isEqualTo(AllowanceStatus.REVOKED);

        Wallet sender = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertThat(sender.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Database Immutability Trigger: Direct SQL tampering of fraud proof payload/signatures fails at DB level")
    void testDatabaseImmutabilityTrigger() {
        // Settle receipt 1 & 2 to generate proof
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("400.0000"), 1L, "nonce-001");
        settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt1)), receiverUser.getId());

        UUID txId2 = UUID.randomUUID();
        OfflineReceiptDto receipt2 = createSignedReceipt(txId2, new BigDecimal("300.0000"), 1L, "nonce-002");
        settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt2)), receiverUser.getId());

        FraudProof proof = fraudProofRepository.findAll().get(0);

        // 1. Attempt to tamper with first_receipt_payload via direct SQL -> MUST FAIL
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE offline_fraud_proofs SET first_receipt_payload = 'malicious-payload' WHERE id = ?",
                proof.getId()
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Cryptographic evidence (payloads, signatures, counter, IDs) in fraud proofs is strictly immutable");

        // 2. Attempt to tamper with first_signature via direct SQL -> MUST FAIL
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE offline_fraud_proofs SET first_signature = 'fake-signature' WHERE id = ?",
                proof.getId()
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Cryptographic evidence (payloads, signatures, counter, IDs) in fraud proofs is strictly immutable");

        // 3. Attempt to DELETE fraud proof -> MUST FAIL
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM offline_fraud_proofs WHERE id = ?",
                proof.getId()
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Offline fraud proofs are strictly immutable and cannot be deleted");

        // 4. Updating status / review fields -> MUST SUCCEED
        int rowsUpdated = jdbcTemplate.update(
                "UPDATE offline_fraud_proofs SET status = 'CONFIRMED', reviewed_by = 'admin_auditor', review_note = 'Confirmed by audit' WHERE id = ?",
                proof.getId()
        );
        assertThat(rowsUpdated).isEqualTo(1);

        FraudProof updated = fraudProofRepository.findById(proof.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(FraudProofStatus.CONFIRMED);
        assertThat(updated.getReviewedBy()).isEqualTo("admin_auditor");
    }

    @Test
    @WithMockUser(username = "regular_user", roles = {"USER"})
    @DisplayName("Admin API RBAC: Non-admin users are rejected with 403 Forbidden")
    void testAdminApiNonAdminForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/offline/fraud-proofs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    @DisplayName("Admin API: Admin can list, retrieve, review, and independently verify fraud proof")
    void testAdminApiLifecycle() throws Exception {
        // Create proof
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("400.0000"), 1L, "nonce-001");
        settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt1)), receiverUser.getId());

        UUID txId2 = UUID.randomUUID();
        OfflineReceiptDto receipt2 = createSignedReceipt(txId2, new BigDecimal("300.0000"), 1L, "nonce-002");
        settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt2)), receiverUser.getId());

        FraudProof proof = fraudProofRepository.findAll().get(0);

        // 1. GET /api/admin/offline/fraud-proofs
        mockMvc.perform(get("/api/admin/offline/fraud-proofs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(proof.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].status").value("OPEN"));

        // 2. GET /api/admin/offline/fraud-proofs/{id}
        mockMvc.perform(get("/api/admin/offline/fraud-proofs/" + proof.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counter").value(1))
                .andExpect(jsonPath("$.data.firstTxId").value(txId1.toString()))
                .andExpect(jsonPath("$.data.secondTxId").value(txId2.toString()));

        // 3. GET /api/admin/offline/fraud-proofs/{id}/verify -> Cryptographic re-verification
        mockMvc.perform(get("/api/admin/offline/fraud-proofs/" + proof.getId() + "/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validProof").value(true))
                .andExpect(jsonPath("$.data.firstSignatureValid").value(true))
                .andExpect(jsonPath("$.data.secondSignatureValid").value(true));

        // 4. POST /api/admin/offline/fraud-proofs/{id}/review -> Review decision
        ReviewFraudProofRequest reviewRequest = new ReviewFraudProofRequest(FraudProofStatus.CONFIRMED, "Manual review confirmed double-spend");
        mockMvc.perform(post("/api/admin/offline/fraud-proofs/" + proof.getId() + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.reviewedBy").value("admin_user"))
                .andExpect(jsonPath("$.data.reviewNote").value("Manual review confirmed double-spend"));
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
