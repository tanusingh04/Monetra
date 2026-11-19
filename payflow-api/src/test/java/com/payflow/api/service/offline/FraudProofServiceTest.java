package com.payflow.api.service.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.offline.OfflineReceiptDto;
import com.payflow.api.metrics.PayFlowMetricsService;
import com.payflow.api.service.WalletBalanceCacheService;
import com.payflow.core.domain.offline.*;
import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudProofServiceTest {

    @Mock
    private FraudProofRepository fraudProofRepository;

    @Mock
    private OfflineAllowanceRepository allowanceRepository;

    @Mock
    private DeviceEnrollmentRepository deviceEnrollmentRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private WalletBalanceCacheService walletBalanceCacheService;

    @Mock
    private PayFlowMetricsService metricsService;

    private OfflineCryptoService cryptoService;
    private ObjectMapper objectMapper;
    private FraudProofService fraudProofService;

    private KeyPair deviceKeyPair;
    private String devicePublicKeyBase64;
    private User testUser;
    private Wallet testWallet;
    private DeviceEnrollment testDevice;
    private OfflineAllowance testAllowance;
    private final String deviceId = "test-device-uuid-123";

    @BeforeEach
    void setUp() throws Exception {
        cryptoService = new OfflineCryptoService();
        objectMapper = new ObjectMapper().findAndRegisterModules();

        fraudProofService = new FraudProofService(
                fraudProofRepository,
                allowanceRepository,
                deviceEnrollmentRepository,
                outboxEventRepository,
                cryptoService,
                walletBalanceCacheService,
                metricsService,
                objectMapper
        );

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        deviceKeyPair = keyGen.generateKeyPair();
        devicePublicKeyBase64 = Base64.getEncoder().encodeToString(deviceKeyPair.getPublic().getEncoded());

        testUser = new User("alice", "alice@test.com", "hash", UserRole.ROLE_USER);
        testWallet = new Wallet(testUser, "INR", new BigDecimal("5000.0000"));
        testDevice = new DeviceEnrollment(testUser, deviceId, "Pixel 8", devicePublicKeyBase64);
        testAllowance = new OfflineAllowance(testUser, testWallet, deviceId,
                new BigDecimal("2000.0000"), new BigDecimal("1000.0000"), Instant.now().plusSeconds(86400));
    }

    @Test
    @DisplayName("Should detect fraud proof on valid equivocation, revoke allowance & device, and publish outbox event")
    void handleCounterConflict_ValidEquivocation_Success() {
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();
        UUID receiver1 = UUID.randomUUID();
        UUID receiver2 = UUID.randomUUID();
        Wallet receiverWallet1 = new Wallet(testUser, "INR", BigDecimal.ZERO);

        String payload1 = cryptoService.buildTransactionPayload(
                txId1, testAllowance.getId(), testWallet.getId(), receiver1,
                new BigDecimal("500.0000"), "INR", 1L, "nonce-1");
        String sig1 = cryptoService.signData(payload1.getBytes(StandardCharsets.UTF_8), deviceKeyPair.getPrivate());

        OfflineTransaction existingTx = new OfflineTransaction(
                txId1, testAllowance, testWallet, receiverWallet1,
                new BigDecimal("500.0000"), "INR", 1L, "nonce-1", sig1, payload1, Instant.now());

        String payload2 = cryptoService.buildTransactionPayload(
                txId2, testAllowance.getId(), testWallet.getId(), receiver2,
                new BigDecimal("300.0000"), "INR", 1L, "nonce-2");
        String sig2 = cryptoService.signData(payload2.getBytes(StandardCharsets.UTF_8), deviceKeyPair.getPrivate());

        OfflineReceiptDto newReceipt = new OfflineReceiptDto(
                txId2, testAllowance.getId(), testWallet.getId(), receiver2,
                new BigDecimal("300.0000"), "INR", 1L, "nonce-2", sig2, Instant.now());

        when(fraudProofRepository.findByAllowanceIdAndCounterAndFirstTxIdAndSecondTxId(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(fraudProofRepository.save(any(FraudProof.class))).thenAnswer(inv -> inv.getArgument(0));

        FraudProofService.FraudEvaluationResult result = fraudProofService.handleCounterConflict(
                newReceipt, existingTx, testAllowance, testDevice);

        assertThat(result.isEquivocation()).isTrue();
        assertThat(result.signaturesValid()).isTrue();
        assertThat(result.proof()).isNotNull();

        // Verify Allowance & Device revoked
        assertThat(testAllowance.getStatus()).isEqualTo(AllowanceStatus.REVOKED);
        assertThat(testDevice.getStatus()).isEqualTo(DeviceStatus.REVOKED);
        verify(allowanceRepository).save(testAllowance);
        verify(deviceEnrollmentRepository).save(testDevice);

        // Verify Outbox Event created
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, atLeastOnce()).save(outboxCaptor.capture());
        List<OutboxEvent> outboxEvents = outboxCaptor.getAllValues();
        boolean hasFraudEvent = outboxEvents.stream().anyMatch(e ->
                "OFFLINE_FRAUD_PROOF".equals(e.getAggregateType()) &&
                "OFFLINE_FRAUD_PROOF_DETECTED".equals(e.getEventType())
        );
        assertThat(hasFraudEvent).isTrue();

        // Verify Metrics
        verify(metricsService).incrementFraudProofsDetected();
    }

    @Test
    @DisplayName("Should reject and create NO proof when second signature is forged (Anti-framing rule)")
    void handleCounterConflict_ForgedSignature_AntiFraming() {
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();
        UUID receiver1 = UUID.randomUUID();
        UUID receiver2 = UUID.randomUUID();
        Wallet receiverWallet1 = new Wallet(testUser, "INR", BigDecimal.ZERO);

        String payload1 = cryptoService.buildTransactionPayload(
                txId1, testAllowance.getId(), testWallet.getId(), receiver1,
                new BigDecimal("500.0000"), "INR", 1L, "nonce-1");
        String sig1 = cryptoService.signData(payload1.getBytes(StandardCharsets.UTF_8), deviceKeyPair.getPrivate());

        OfflineTransaction existingTx = new OfflineTransaction(
                txId1, testAllowance, testWallet, receiverWallet1,
                new BigDecimal("500.0000"), "INR", 1L, "nonce-1", sig1, payload1, Instant.now());

        // Receipt 2 has forged signature
        OfflineReceiptDto newReceipt = new OfflineReceiptDto(
                txId2, testAllowance.getId(), testWallet.getId(), receiver2,
                new BigDecimal("300.0000"), "INR", 1L, "nonce-2",
                Base64.getEncoder().encodeToString(new byte[64]), Instant.now());

        FraudProofService.FraudEvaluationResult result = fraudProofService.handleCounterConflict(
                newReceipt, existingTx, testAllowance, testDevice);

        assertThat(result.isEquivocation()).isTrue();
        assertThat(result.signaturesValid()).isFalse();
        assertThat(result.proof()).isNull();
        assertThat(result.rejectionReason()).contains("Anti-framing protection triggered");

        // Verify allowance and device remain unchanged
        assertThat(testAllowance.getStatus()).isEqualTo(AllowanceStatus.ACTIVE);
        assertThat(testDevice.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
        verify(fraudProofRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
        verify(metricsService).incrementFraudProofsRejectedInvalidSignature();
    }

    @Test
    @DisplayName("Should detect identical receipt resubmission and NOT create fraud proof")
    void handleCounterConflict_IdenticalReceipt_NoFraudProof() {
        UUID txId1 = UUID.randomUUID();
        UUID receiver1 = UUID.randomUUID();
        Wallet receiverWallet1 = new Wallet(testUser, "INR", BigDecimal.ZERO);

        String payload1 = cryptoService.buildTransactionPayload(
                txId1, testAllowance.getId(), testWallet.getId(), receiver1,
                new BigDecimal("500.0000"), "INR", 1L, "nonce-1");
        String sig1 = cryptoService.signData(payload1.getBytes(StandardCharsets.UTF_8), deviceKeyPair.getPrivate());

        OfflineTransaction existingTx = new OfflineTransaction(
                txId1, testAllowance, testWallet, receiverWallet1,
                new BigDecimal("500.0000"), "INR", 1L, "nonce-1", sig1, payload1, Instant.now());

        // Resubmission of identical receipt
        OfflineReceiptDto resubmittedReceipt = new OfflineReceiptDto(
                txId1, testAllowance.getId(), testWallet.getId(), receiver1,
                new BigDecimal("500.0000"), "INR", 1L, "nonce-1", sig1, Instant.now());

        FraudProofService.FraudEvaluationResult result = fraudProofService.handleCounterConflict(
                resubmittedReceipt, existingTx, testAllowance, testDevice);

        assertThat(result.isEquivocation()).isFalse();
        assertThat(result.proof()).isNull();
        verify(fraudProofRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should independently verify stored fraud proof signatures")
    void verifyFraudProof_ValidSignatures() {
        UUID proofId = UUID.randomUUID();
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();

        String payload1 = cryptoService.buildTransactionPayload(
                txId1, testAllowance.getId(), testWallet.getId(), UUID.randomUUID(),
                new BigDecimal("500.0000"), "INR", 1L, "nonce-1");
        String sig1 = cryptoService.signData(payload1.getBytes(StandardCharsets.UTF_8), deviceKeyPair.getPrivate());

        String payload2 = cryptoService.buildTransactionPayload(
                txId2, testAllowance.getId(), testWallet.getId(), UUID.randomUUID(),
                new BigDecimal("300.0000"), "INR", 1L, "nonce-2");
        String sig2 = cryptoService.signData(payload2.getBytes(StandardCharsets.UTF_8), deviceKeyPair.getPrivate());

        FraudProof proof = new FraudProof(
                testAllowance, deviceId, testWallet, 1L, txId1, txId2, payload1, sig1, payload2, sig2
        );

        when(fraudProofRepository.findById(proofId)).thenReturn(Optional.of(proof));
        when(deviceEnrollmentRepository.findByDeviceId(deviceId)).thenReturn(Optional.of(testDevice));

        FraudProofService.FraudProofVerificationResult result = fraudProofService.verifyFraudProof(proofId);

        assertThat(result.isValidProof()).isTrue();
        assertThat(result.firstSignatureValid()).isTrue();
        assertThat(result.secondSignatureValid()).isTrue();
        assertThat(result.details()).contains("Cryptographic proof independently verified");
    }
}
