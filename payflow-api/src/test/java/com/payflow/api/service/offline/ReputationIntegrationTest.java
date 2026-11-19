package com.payflow.api.service.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.config.ReputationProperties;
import com.payflow.api.dto.offline.AllocateAllowanceRequest;
import com.payflow.api.dto.offline.AllowanceCertificateResponse;
import com.payflow.api.dto.offline.EnrollDeviceRequest;
import com.payflow.api.dto.offline.OfflineReceiptDto;
import com.payflow.api.dto.reputation.ReputationOverrideRequest;
import com.payflow.api.dto.reputation.ReputationResponse;
import com.payflow.core.domain.offline.AllowanceStatus;
import com.payflow.core.domain.offline.DeviceStatus;
import com.payflow.core.domain.offline.FraudProofStatus;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.offline.OfflineTransaction;
import com.payflow.core.domain.offline.OfflineTransactionStatus;
import com.payflow.core.domain.reputation.ReputationEvent;
import com.payflow.core.domain.reputation.ReputationEventType;
import com.payflow.core.domain.reputation.ReputationTier;
import com.payflow.core.domain.reputation.UserReputation;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.exception.ReputationLimitExceededException;
import com.payflow.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class ReputationIntegrationTest {

    @Autowired
    private ReputationService reputationService;

    @Autowired
    private OfflineAllowanceService allowanceService;

    @Autowired
    private DeviceEnrollmentService deviceEnrollmentService;

    @Autowired
    private OfflineSettlementService settlementService;

    @Autowired
    private OfflineCryptoService cryptoService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private UserReputationRepository userReputationRepository;

    @Autowired
    private ReputationEventRepository reputationEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OfflineAllowanceRepository allowanceRepository;

    @Autowired
    private DeviceEnrollmentRepository deviceEnrollmentRepository;

    @Autowired
    private OfflineTransactionRepository offlineTransactionRepository;

    @Autowired
    private OfflineProtectedSettlementRepository protectedSettlementRepository;

    @Autowired
    private OfflineRejectedReceiptRepository rejectedReceiptRepository;

    @Autowired
    private FraudProofRepository fraudProofRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReputationProperties reputationProperties;

    private User alice;
    private User bob;
    private Wallet aliceWallet;
    private Wallet bobWallet;
    private KeyPair aliceDeviceKeyPair;
    private String alicePublicKeyBase64;
    private final String aliceDeviceId = "alice-phone-01";

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

        alice = userRepository.save(new User("alice", "alice@rep.test", "hash", UserRole.ROLE_USER));
        bob = userRepository.save(new User("bob", "bob@rep.test", "hash", UserRole.ROLE_USER));

        aliceWallet = walletRepository.save(new Wallet(alice, "INR", new BigDecimal("10000.0000")));
        bobWallet = walletRepository.save(new Wallet(bob, "INR", new BigDecimal("5000.0000")));

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        aliceDeviceKeyPair = keyGen.generateKeyPair();
        alicePublicKeyBase64 = Base64.getEncoder().encodeToString(aliceDeviceKeyPair.getPublic().getEncoded());

        deviceEnrollmentService.enrollDevice(
                new EnrollDeviceRequest(aliceDeviceId, "Pixel 8", alicePublicKeyBase64),
                alice.getId()
        );

        // Register H2 immutability trigger for reputation_events
        try {
            jdbcTemplate.execute("CREATE TRIGGER trg_immutable_reputation_events BEFORE UPDATE, DELETE ON reputation_events FOR EACH ROW CALL \"com.payflow.api.service.offline.H2ReputationEventsImmutabilityTrigger\"");
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("2a/2f: Initial reputation profile creation with starting score and audit log")
    void testInitialReputationProfile() {
        ReputationResponse response = reputationService.getReputationResponse(alice.getId());

        assertThat(response.score()).isEqualTo(reputationProperties.getStartingScore());
        assertThat(response.tier()).isEqualTo(ReputationTier.NEW);
        assertThat(response.restricted()).isFalse();
        assertThat(response.cleanSettledCount()).isEqualTo(0);
        assertThat(response.rejectedCount()).isEqualTo(0);
        assertThat(response.recentEvents()).hasSize(1);
        assertThat(response.recentEvents().get(0).eventType()).isEqualTo(ReputationEventType.INITIAL_ENROLLMENT);
    }

    @Test
    @DisplayName("2e: Allocate allowance above tier limit fails with HTTP 422 exception")
    void testAllocateAboveTierLimitFailsWith422() {
        // Alice starts in NEW tier (test limit: 2500 max allowance, 1000 max single)
        // Request 3000 -> exceeds 2500 limit
        AllocateAllowanceRequest excessiveAllowance = new AllocateAllowanceRequest(
                aliceWallet.getId(), aliceDeviceId, new BigDecimal("3000.0000"), new BigDecimal("500.0000"), 24
        );

        ReputationLimitExceededException ex = assertThrows(ReputationLimitExceededException.class, () ->
                allowanceService.allocateAllowance(excessiveAllowance, alice.getId())
        );

        assertThat(ex.getMessage()).contains("Requested allowance 3000.0000 exceeds maximum allowance limit of 2500.0000 for tier NEW");

        // Request 2000 total but 1200 single tx -> exceeds 1000 single tx limit
        AllocateAllowanceRequest excessiveSingleTx = new AllocateAllowanceRequest(
                aliceWallet.getId(), aliceDeviceId, new BigDecimal("2000.0000"), new BigDecimal("1200.0000"), 24
        );

        ReputationLimitExceededException ex2 = assertThrows(ReputationLimitExceededException.class, () ->
                allowanceService.allocateAllowance(excessiveSingleTx, alice.getId())
        );

        assertThat(ex2.getMessage()).contains("Requested single transaction limit 1200.0000 exceeds maximum single transaction limit of 1000.0000 for tier NEW");
    }

    @Test
    @DisplayName("2c: Clean settlement increases score and records immutable audit event inside transaction")
    void testCleanSettlementIncreasesScore() {
        AllowanceCertificateResponse cert = allowanceService.allocateAllowance(
                new AllocateAllowanceRequest(aliceWallet.getId(), aliceDeviceId, new BigDecimal("1000.0000"), new BigDecimal("500.0000"), 24),
                alice.getId()
        );

        UUID txId = UUID.randomUUID();
        long counter = 1L;
        String nonce = "nonce-clean-1";
        BigDecimal amount = new BigDecimal("200.0000");

        String payload = cryptoService.buildTransactionPayload(
                txId, cert.allowanceId(), aliceWallet.getId(), bobWallet.getId(), amount, "INR", counter, nonce
        );
        String sig = cryptoService.signData(payload.getBytes(), aliceDeviceKeyPair.getPrivate());

        OfflineReceiptDto receipt = new OfflineReceiptDto(
                txId, cert.allowanceId(), aliceWallet.getId(), bobWallet.getId(), amount, "INR", counter, nonce, sig, Instant.now()
        );

        var result = settlementService.settleOfflineBatch(new com.payflow.api.dto.offline.OfflineSyncRequest(List.of(receipt)), bob.getId());
        assertThat(result.totalSettled()).isEqualTo(1);

        ReputationResponse rep = reputationService.getReputationResponse(alice.getId());
        assertThat(rep.cleanSettledCount()).isEqualTo(1);
        assertThat(rep.score()).isEqualTo(reputationProperties.getStartingScore() + reputationProperties.getCleanReceiptPoints());
        assertThat(rep.settledAmountTotal()).isEqualByComparingTo(new BigDecimal("200.0000"));

        List<ReputationEvent> events = reputationEventRepository.findByUserIdOrderByCreatedAtDesc(alice.getId());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getEventType()).isEqualTo(ReputationEventType.CLEAN_SETTLEMENT);
        assertThat(events.get(0).getScoreDelta()).isEqualTo(reputationProperties.getCleanReceiptPoints());
    }

    @Test
    @DisplayName("2c: Sender-attributable rejection (invalid signature) penalizes score")
    void testSenderAttributableRejectionPenalizesScore() {
        AllowanceCertificateResponse cert = allowanceService.allocateAllowance(
                new AllocateAllowanceRequest(aliceWallet.getId(), aliceDeviceId, new BigDecimal("1000.0000"), new BigDecimal("500.0000"), 24),
                alice.getId()
        );

        UUID txId = UUID.randomUUID();
        // Bad signature
        OfflineReceiptDto badSigReceipt = new OfflineReceiptDto(
                txId, cert.allowanceId(), aliceWallet.getId(), bobWallet.getId(),
                new BigDecimal("100.0000"), "INR", 1L, "nonce-bad", Base64.getEncoder().encodeToString(new byte[64]), Instant.now()
        );

        var result = settlementService.settleOfflineBatch(new com.payflow.api.dto.offline.OfflineSyncRequest(List.of(badSigReceipt)), bob.getId());
        assertThat(result.totalRejected()).isEqualTo(1);

        ReputationResponse rep = reputationService.getReputationResponse(alice.getId());
        assertThat(rep.rejectedCount()).isEqualTo(1);
        assertThat(rep.score()).isEqualTo(reputationProperties.getStartingScore() - reputationProperties.getRejectionPenalty());

        List<ReputationEvent> events = reputationEventRepository.findByUserIdOrderByCreatedAtDesc(alice.getId());
        assertThat(events.get(0).getEventType()).isEqualTo(ReputationEventType.SENDER_REJECTION_PENALTY);
        assertThat(events.get(0).getScoreDelta()).isEqualTo(-reputationProperties.getRejectionPenalty());
    }

    @Test
    @DisplayName("2c: Fraud proof puts user in RESTRICTED tier immediately and blocks future allowances")
    void testFraudProofRestrictsUserAndBlocksAllowances() {
        AllowanceCertificateResponse cert = allowanceService.allocateAllowance(
                new AllocateAllowanceRequest(aliceWallet.getId(), aliceDeviceId, new BigDecimal("1500.0000"), new BigDecimal("500.0000"), 24),
                alice.getId()
        );

        long counter = 1L;
        // Receipt 1
        UUID tx1 = UUID.randomUUID();
        String payload1 = cryptoService.buildTransactionPayload(
                tx1, cert.allowanceId(), aliceWallet.getId(), bobWallet.getId(), new BigDecimal("100.0000"), "INR", counter, "n1"
        );
        String sig1 = cryptoService.signData(payload1.getBytes(), aliceDeviceKeyPair.getPrivate());
        OfflineReceiptDto receipt1 = new OfflineReceiptDto(
                tx1, cert.allowanceId(), aliceWallet.getId(), bobWallet.getId(), new BigDecimal("100.0000"), "INR", counter, "n1", sig1, Instant.now()
        );
        settlementService.settleOfflineBatch(new com.payflow.api.dto.offline.OfflineSyncRequest(List.of(receipt1)), bob.getId());

        // Receipt 2 (Conflicting counter equivocation!)
        UUID tx2 = UUID.randomUUID();
        String payload2 = cryptoService.buildTransactionPayload(
                tx2, cert.allowanceId(), aliceWallet.getId(), bobWallet.getId(), new BigDecimal("200.0000"), "INR", counter, "n2"
        );
        String sig2 = cryptoService.signData(payload2.getBytes(), aliceDeviceKeyPair.getPrivate());
        OfflineReceiptDto receipt2 = new OfflineReceiptDto(
                tx2, cert.allowanceId(), aliceWallet.getId(), bobWallet.getId(), new BigDecimal("200.0000"), "INR", counter, "n2", sig2, Instant.now()
        );
        settlementService.settleOfflineBatch(new com.payflow.api.dto.offline.OfflineSyncRequest(List.of(receipt2)), bob.getId());

        ReputationResponse rep = reputationService.getReputationResponse(alice.getId());
        assertThat(rep.restricted()).isTrue();
        assertThat(rep.tier()).isEqualTo(ReputationTier.RESTRICTED);
        assertThat(rep.fraudProofsDetected()).isEqualTo(1);

        // Attempting to request a new allowance while RESTRICTED fails with 422
        deviceEnrollmentService.enrollDevice(new EnrollDeviceRequest("alice-tablet", "iPad", alicePublicKeyBase64), alice.getId());
        AllocateAllowanceRequest requestWhileRestricted = new AllocateAllowanceRequest(
                aliceWallet.getId(), "alice-tablet", new BigDecimal("100.0000"), new BigDecimal("50.0000"), 24
        );

        ReputationLimitExceededException ex = assertThrows(ReputationLimitExceededException.class, () ->
                allowanceService.allocateAllowance(requestWhileRestricted, alice.getId())
        );
        assertThat(ex.getMessage()).contains("RESTRICTED");
    }

    @Test
    @DisplayName("2h: Concurrent settlements update the score consistently")
    void testConcurrentSettlementsScoreConsistency() throws Exception {
        AllowanceCertificateResponse cert = allowanceService.allocateAllowance(
                new AllocateAllowanceRequest(aliceWallet.getId(), aliceDeviceId, new BigDecimal("2000.0000"), new BigDecimal("500.0000"), 24),
                alice.getId()
        );

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<OfflineReceiptDto> receipts = new ArrayList<>();
        for (int i = 1; i <= threadCount; i++) {
            UUID txId = UUID.randomUUID();
            long counter = i;
            String nonce = "nonce-conc-" + i;
            BigDecimal amt = new BigDecimal("50.0000");
            String payload = cryptoService.buildTransactionPayload(
                    txId, cert.allowanceId(), aliceWallet.getId(), bobWallet.getId(), amt, "INR", counter, nonce
            );
            String sig = cryptoService.signData(payload.getBytes(), aliceDeviceKeyPair.getPrivate());
            receipts.add(new OfflineReceiptDto(
                    txId, cert.allowanceId(), aliceWallet.getId(), bobWallet.getId(), amt, "INR", counter, nonce, sig, Instant.now()
            ));
        }

        for (OfflineReceiptDto receipt : receipts) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    settlementService.settleOfflineBatch(new com.payflow.api.dto.offline.OfflineSyncRequest(List.of(receipt)), bob.getId());
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();

        ReputationResponse rep = reputationService.getReputationResponse(alice.getId());
        assertThat(rep.cleanSettledCount()).isEqualTo(threadCount);
        assertThat(rep.score()).isEqualTo(reputationProperties.getStartingScore() + (threadCount * reputationProperties.getCleanReceiptPoints()));
        assertThat(rep.settledAmountTotal()).isEqualByComparingTo(new BigDecimal("200.0000"));
    }

    @Test
    @DisplayName("2a: reputation_events table is strictly immutable (H2 trigger blocks UPDATE and DELETE)")
    void testReputationEventsImmutability() {
        reputationService.getOrCreateReputation(alice.getId());
        List<ReputationEvent> events = reputationEventRepository.findByUserIdOrderByCreatedAtDesc(alice.getId());
        assertThat(events).isNotEmpty();
        UUID eventId = events.get(0).getId();

        // Attempt direct UPDATE via SQL
        assertThrows(Exception.class, () ->
                jdbcTemplate.execute("UPDATE reputation_events SET score_delta = 999 WHERE id = '" + eventId + "'")
        );

        // Attempt direct DELETE via SQL
        assertThrows(Exception.class, () ->
                jdbcTemplate.execute("DELETE FROM reputation_events WHERE id = '" + eventId + "'")
        );
    }

    @Test
    @DisplayName("2f: Admin override modifies score/tier and creates audit event")
    void testAdminOverride() {
        reputationService.getOrCreateReputation(alice.getId());

        ReputationOverrideRequest request = new ReputationOverrideRequest(
                ReputationTier.GOLD,
                1500,
                false,
                "Promoted to VIP status following KYC verification"
        );

        ReputationResponse overridden = reputationService.adminOverride(alice.getId(), request, "admin_user");

        assertThat(overridden.tier()).isEqualTo(ReputationTier.GOLD);
        assertThat(overridden.score()).isEqualTo(1500);

        List<ReputationEvent> events = reputationEventRepository.findByUserIdOrderByCreatedAtDesc(alice.getId());
        assertThat(events.get(0).getEventType()).isEqualTo(ReputationEventType.ADMIN_OVERRIDE);
        assertThat(events.get(0).getReason()).contains("admin_user");
        assertThat(events.get(0).getReason()).contains("Promoted to VIP status");
    }
}
