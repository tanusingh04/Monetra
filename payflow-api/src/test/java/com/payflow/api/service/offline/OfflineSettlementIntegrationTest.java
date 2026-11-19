package com.payflow.api.service.offline;

import com.payflow.api.dto.offline.*;
import com.payflow.core.domain.ledger.EntryType;
import com.payflow.core.domain.ledger.LedgerEntry;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class OfflineSettlementIntegrationTest {

    @Autowired
    private DeviceEnrollmentService deviceEnrollmentService;

    @Autowired
    private OfflineAllowanceService allowanceService;

    @Autowired
    private OfflineSettlementService settlementService;

    @Autowired
    private OfflineCryptoService cryptoService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private OfflineAllowanceRepository allowanceRepository;

    @Autowired
    private OfflineTransactionRepository offlineTransactionRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private DeviceEnrollmentRepository deviceEnrollmentRepository;

    @Autowired
    private FraudProofRepository fraudProofRepository;

    @Autowired
    private OfflineProtectedSettlementRepository protectedSettlementRepository;

    @Autowired
    private OfflineRejectedReceiptRepository rejectedReceiptRepository;

    @Autowired
    private ReputationEventRepository reputationEventRepository;

    @Autowired
    private UserReputationRepository userReputationRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private User senderUser;
    private User receiverUser;
    private Wallet senderWallet;
    private Wallet receiverWallet;

    private KeyPair deviceKeyPair;
    private String devicePublicKeyBase64;
    private final String deviceId = "android-test-device-42";
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
        ledgerEntryRepository.deleteAll();
        paymentRepository.deleteAll();
        walletRepository.deleteAll();
        reputationEventRepository.deleteAll();
        userReputationRepository.deleteAll();
        notificationRepository.deleteAll();
        userRepository.deleteAll();

        // 1. Create Users & Wallets
        senderUser = userRepository.save(new User("offline_sender", "sender@offline.test", "hash", UserRole.ROLE_USER));
        receiverUser = userRepository.save(new User("offline_receiver", "receiver@offline.test", "hash", UserRole.ROLE_USER));

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
    }

    @Test
    @DisplayName("Should successfully settle offline batch, update balances, and enforce double-entry ledger")
    void shouldSettleOfflineBatchSuccessfully() {
        // Assert initial balance reservation
        Wallet refreshedSender = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertEquals(new BigDecimal("2000.0000"), refreshedSender.getReservedBalance());
        assertEquals(new BigDecimal("3000.0000"), refreshedSender.getAvailableBalance());

        // Create 2 Offline Receipts: ₹600 and ₹400
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("600.0000"), 1L, "nonce-001");

        UUID txId2 = UUID.randomUUID();
        OfflineReceiptDto receipt2 = createSignedReceipt(txId2, new BigDecimal("400.0000"), 2L, "nonce-002");

        // Execute Settlement
        OfflineSyncRequest syncRequest = new OfflineSyncRequest(List.of(receipt1, receipt2));
        OfflineSyncResponse response = settlementService.settleOfflineBatch(syncRequest, receiverUser.getId());

        assertThat(response.totalSubmitted()).isEqualTo(2);
        assertThat(response.totalSettled()).isEqualTo(2);
        assertThat(response.totalRejected()).isEqualTo(0);

        // Verify Balances Post-Settlement
        refreshedSender = walletRepository.findById(senderWallet.getId()).orElseThrow();
        Wallet refreshedReceiver = walletRepository.findById(receiverWallet.getId()).orElseThrow();

        // Sender: 5000 - 1000 = 4000 total balance, reserved = 2000 - 1000 = 1000, available = 3000
        assertEquals(new BigDecimal("4000.0000"), refreshedSender.getBalance());
        assertEquals(new BigDecimal("1000.0000"), refreshedSender.getReservedBalance());
        assertEquals(new BigDecimal("3000.0000"), refreshedSender.getAvailableBalance());

        // Receiver: 1000 + 1000 = 2000 balance
        assertEquals(new BigDecimal("2000.0000"), refreshedReceiver.getBalance());

        // Verify Double-Entry Ledger Parity
        BigDecimal totalDebits = ledgerEntryRepository.sumAmountByEntryType(EntryType.DEBIT);
        BigDecimal totalCredits = ledgerEntryRepository.sumAmountByEntryType(EntryType.CREDIT);
        assertThat(totalDebits).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(totalCredits).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(totalDebits).isEqualByComparingTo(totalCredits);

        // Replay submission (Idempotency) -> Should return ALREADY_SETTLED without duplicate debit
        OfflineSyncResponse replayResponse = settlementService.settleOfflineBatch(syncRequest, receiverUser.getId());
        assertThat(replayResponse.totalSettled()).isEqualTo(2);
        assertThat(replayResponse.results().get(0).status()).isEqualTo("ALREADY_SETTLED");

        refreshedSender = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertEquals(new BigDecimal("4000.0000"), refreshedSender.getBalance());
    }

    @Test
    @DisplayName("Should detect fraud proof on duplicate counter collision and reject equivocation")
    void shouldRejectCounterCollision() {
        UUID txId1 = UUID.randomUUID();
        OfflineReceiptDto receipt1 = createSignedReceipt(txId1, new BigDecimal("300.0000"), 1L, "nonce-001");
        settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt1)), receiverUser.getId());

        // Second receipt with same counter 1L but exceeding max single limit (uncovered equivocation)
        UUID txId2 = UUID.randomUUID();
        OfflineReceiptDto duplicateCounterReceipt = createSignedReceipt(txId2, new BigDecimal("1500.0000"), 1L, "nonce-dup");
        OfflineSyncResponse response = settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(duplicateCounterReceipt)), receiverUser.getId());

        assertThat(response.totalRejected()).isEqualTo(1);
        assertThat(response.results().get(0).message()).contains("FRAUD_PROOF_DETECTED:");
        assertThat(response.results().get(0).message()).contains("UNCOVERED_BY_RESERVE");
        assertThat(allowanceRepository.findById(allowanceCert.allowanceId()).orElseThrow().getStatus()).isEqualTo(com.payflow.core.domain.offline.AllowanceStatus.REVOKED);
    }

    @Test
    @DisplayName("Should reject receipt with invalid or forged signature")
    void shouldRejectInvalidSignature() {
        UUID txId = UUID.randomUUID();
        OfflineReceiptDto forgedReceipt = new OfflineReceiptDto(
                txId,
                allowanceCert.allowanceId(),
                senderWallet.getId(),
                receiverWallet.getId(),
                new BigDecimal("100.0000"),
                "INR",
                1L,
                "nonce-forged",
                Base64.getEncoder().encodeToString(new byte[64]), // Fake signature
                Instant.now()
        );

        OfflineSyncResponse response = settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(forgedReceipt)), receiverUser.getId());
        assertThat(response.totalRejected()).isEqualTo(1);
        assertThat(response.results().get(0).message()).contains("Invalid cryptographic device signature");
    }

    @Test
    @DisplayName("Should release unspent reserved funds when allowance is expired or released")
    void shouldReleaseUnspentAllowanceFunds() {
        // Spend ₹500 offline
        UUID txId = UUID.randomUUID();
        OfflineReceiptDto receipt = createSignedReceipt(txId, new BigDecimal("500.0000"), 1L, "nonce-spend");
        settlementService.settleOfflineBatch(new OfflineSyncRequest(List.of(receipt)), receiverUser.getId());

        // Balance: 4500, Reserved: 1500, Available: 3000
        Wallet senderBeforeRelease = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertEquals(new BigDecimal("1500.0000"), senderBeforeRelease.getReservedBalance());

        // Release allowance
        allowanceService.releaseAllowance(allowanceCert.allowanceId(), senderUser.getId());

        // Reserved balance should drop to 0, available balance becomes 4500
        Wallet senderAfterRelease = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertEquals(new BigDecimal("0.0000"), senderAfterRelease.getReservedBalance());
        assertEquals(new BigDecimal("4500.0000"), senderAfterRelease.getAvailableBalance());
        assertEquals(new BigDecimal("4500.0000"), senderAfterRelease.getBalance());
    }

    private OfflineReceiptDto createSignedReceipt(UUID txId, BigDecimal amount, long counter, String nonce) {
        String payload = cryptoService.buildTransactionPayload(
                txId, allowanceCert.allowanceId(), senderWallet.getId(), receiverWallet.getId(),
                amount, "INR", counter, nonce
        );
        String signature = cryptoService.signData(payload.getBytes(), deviceKeyPair.getPrivate());
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
