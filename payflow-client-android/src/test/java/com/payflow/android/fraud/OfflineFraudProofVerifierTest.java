package com.payflow.android.fraud;

import com.payflow.android.crypto.StandardSecureKeyStore;
import com.payflow.android.model.PaymentReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OfflineFraudProofVerifierTest {

    private StandardSecureKeyStore keyStore;
    private OfflineFraudProofVerifier verifier;
    private String devicePublicKey;
    private final String alias = "test-device-alias";
    private final UUID allowanceId = UUID.randomUUID();
    private final UUID senderWalletId = UUID.randomUUID();
    private final UUID receiverWalletId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        keyStore = new StandardSecureKeyStore();
        keyStore.initializeKey(alias);
        devicePublicKey = keyStore.getPublicKeyBase64(alias);
        verifier = new OfflineFraudProofVerifier(keyStore);
    }

    @Test
    @DisplayName("Should detect fraud proof when two validly signed receipts share allowance and counter but have different payloads")
    void shouldDetectFraudProofOnEquivocation() {
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();

        PaymentReceipt r1 = createReceipt(txId1, allowanceId, 1L, new BigDecimal("100.0000"), "nonce-1");
        PaymentReceipt r2 = createReceipt(txId2, allowanceId, 1L, new BigDecimal("200.0000"), "nonce-2");

        OfflineFraudProofVerifier.FraudProofCheckResult result = verifier.verifyFraudProof(r1, r2, devicePublicKey);

        assertTrue(result.isFraudProof());
        assertTrue(result.firstSignatureValid());
        assertTrue(result.secondSignatureValid());
        assertTrue(result.reason().contains("equivocation fraud proof confirmed"));
    }

    @Test
    @DisplayName("Should reject fraud proof if second receipt has invalid signature (Anti-framing protection)")
    void shouldRejectWhenSignatureInvalid_AntiFraming() {
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();

        PaymentReceipt r1 = createReceipt(txId1, allowanceId, 1L, new BigDecimal("100.0000"), "nonce-1");
        // Create r2 with fake signature
        PaymentReceipt r2 = new PaymentReceipt(
                txId2, allowanceId, senderWalletId, receiverWalletId,
                new BigDecimal("200.0000"), "INR", 1L, "nonce-2",
                "MEYCIQCfakeSignatureThatDoesNotVerify===", Instant.now()
        );

        OfflineFraudProofVerifier.FraudProofCheckResult result = verifier.verifyFraudProof(r1, r2, devicePublicKey);

        assertFalse(result.isFraudProof());
        assertTrue(result.firstSignatureValid());
        assertFalse(result.secondSignatureValid());
        assertTrue(result.reason().contains("Anti-framing protection triggered"));
    }

    @Test
    @DisplayName("Should not detect fraud proof when receipts are identical (idempotent replay)")
    void shouldNotDetectFraudProofOnIdenticalReceipts() {
        UUID txId1 = UUID.randomUUID();
        PaymentReceipt r1 = createReceipt(txId1, allowanceId, 1L, new BigDecimal("100.0000"), "nonce-1");
        PaymentReceipt r2 = createReceipt(txId1, allowanceId, 1L, new BigDecimal("100.0000"), "nonce-1");

        OfflineFraudProofVerifier.FraudProofCheckResult result = verifier.verifyFraudProof(r1, r2, devicePublicKey);

        assertFalse(result.isFraudProof());
        assertTrue(result.reason().contains("Identical receipt resubmission"));
    }

    @Test
    @DisplayName("Should not detect fraud proof when counters differ")
    void shouldNotDetectFraudProofOnDifferentCounters() {
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();

        PaymentReceipt r1 = createReceipt(txId1, allowanceId, 1L, new BigDecimal("100.0000"), "nonce-1");
        PaymentReceipt r2 = createReceipt(txId2, allowanceId, 2L, new BigDecimal("200.0000"), "nonce-2");

        OfflineFraudProofVerifier.FraudProofCheckResult result = verifier.verifyFraudProof(r1, r2, devicePublicKey);

        assertFalse(result.isFraudProof());
        assertTrue(result.reason().contains("Counters do not match"));
    }

    @Test
    @DisplayName("Should not detect fraud proof when allowances differ")
    void shouldNotDetectFraudProofOnDifferentAllowances() {
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();

        PaymentReceipt r1 = createReceipt(txId1, allowanceId, 1L, new BigDecimal("100.0000"), "nonce-1");
        PaymentReceipt r2 = createReceipt(txId2, UUID.randomUUID(), 1L, new BigDecimal("200.0000"), "nonce-2");

        OfflineFraudProofVerifier.FraudProofCheckResult result = verifier.verifyFraudProof(r1, r2, devicePublicKey);

        assertFalse(result.isFraudProof());
        assertTrue(result.reason().contains("belong to different allowances"));
    }

    private PaymentReceipt createReceipt(UUID txId, UUID allocId, long counter, BigDecimal amount, String nonce) {
        PaymentReceipt temp = new PaymentReceipt(
                txId, allocId, senderWalletId, receiverWalletId, amount, "INR", counter, nonce, "", Instant.now()
        );
        String payload = temp.toSigningPayload();
        String signature = keyStore.sign(alias, payload.getBytes(StandardCharsets.UTF_8));
        return new PaymentReceipt(
                txId, allocId, senderWalletId, receiverWalletId, amount, "INR", counter, nonce, signature, Instant.now()
        );
    }
}
