package com.payflow.android.p2p;

import com.payflow.android.crypto.StandardSecureKeyStore;
import com.payflow.android.model.AllowanceCertificate;
import com.payflow.android.model.PaymentRequest;
import com.payflow.android.model.PaymentResponse;
import com.payflow.android.storage.EncryptedOfflineStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class P2PPaymentProtocolTest {

    private StandardSecureKeyStore keyStore;
    private EncryptedOfflineStorage senderStorage;
    private EncryptedOfflineStorage receiverStorage;
    private P2PPaymentProtocol senderProtocol;
    private P2PPaymentProtocol receiverProtocol;

    private final String authorityAlias = "authority-ca";
    private final String senderAlias = "sender-device";

    private UUID senderWalletId;
    private UUID receiverWalletId;
    private AllowanceCertificate validCertificate;

    @BeforeEach
    void setUp() {
        keyStore = new StandardSecureKeyStore();
        keyStore.initializeKey(authorityAlias);
        keyStore.initializeKey(senderAlias);

        senderStorage = new EncryptedOfflineStorage();
        receiverStorage = new EncryptedOfflineStorage();

        senderProtocol = new P2PPaymentProtocol(keyStore, senderStorage);
        receiverProtocol = new P2PPaymentProtocol(keyStore, receiverStorage);

        senderWalletId = UUID.randomUUID();
        receiverWalletId = UUID.randomUUID();

        // Create and sign allowance certificate using authority key
        UUID allowanceId = UUID.randomUUID();
        BigDecimal allocated = new BigDecimal("1000.0000");
        BigDecimal maxSingle = new BigDecimal("500.0000");
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));

        String certPayload = String.format("%s|%s|%s|%s|%s|%d",
                allowanceId, senderWalletId, "dev-123", allocated.toPlainString(), maxSingle.toPlainString(), expiresAt.getEpochSecond());
        String serverSignature = keyStore.sign(authorityAlias, certPayload.getBytes(StandardCharsets.UTF_8));

        validCertificate = new AllowanceCertificate(
                allowanceId,
                senderWalletId,
                "dev-123",
                allocated,
                maxSingle,
                expiresAt,
                serverSignature,
                keyStore.getPublicKeyBase64(authorityAlias)
        );

        senderStorage.saveAllowance(validCertificate);
    }

    @Test
    @DisplayName("Should successfully execute P2P payment and verify receipt offline")
    void shouldSuccessfullyExecuteAndVerifyPayment() {
        BigDecimal paymentAmount = new BigDecimal("250.0000");

        // Step 1: Receiver creates payment request with nonce
        PaymentRequest request = receiverProtocol.createPaymentRequest(receiverWalletId, paymentAmount, "INR");
        assertNotNull(request.nonce());

        // Step 2: Sender processes request and generates signed receipt
        PaymentResponse response = senderProtocol.processPaymentRequest(request, senderAlias);
        assertNotNull(response.receipt().deviceSignature());
        assertEquals(new BigDecimal("750.0000"), senderStorage.getRemainingBalance());
        assertEquals(1L, response.receipt().counter());

        // Step 3: Receiver verifies and accepts payment offline
        boolean accepted = receiverProtocol.verifyAndAcceptPayment(response, request);
        assertTrue(accepted);
        assertEquals(1, receiverStorage.getPendingReceiptsForSync().size());
    }

    @Test
    @DisplayName("Should reject payment when requested amount exceeds single transaction limit")
    void shouldRejectWhenExceedingSingleTxLimit() {
        BigDecimal overLimitAmount = new BigDecimal("600.0000"); // Limit is 500
        PaymentRequest request = receiverProtocol.createPaymentRequest(receiverWalletId, overLimitAmount, "INR");

        assertThrows(IllegalArgumentException.class, () ->
                senderProtocol.processPaymentRequest(request, senderAlias)
        );
    }

    @Test
    @DisplayName("Should reject payment when offline balance is exhausted")
    void shouldRejectWhenBalanceExhausted() {
        // Spend 500
        PaymentRequest req1 = receiverProtocol.createPaymentRequest(receiverWalletId, new BigDecimal("500.0000"), "INR");
        senderProtocol.processPaymentRequest(req1, senderAlias);

        // Spend 500
        PaymentRequest req2 = receiverProtocol.createPaymentRequest(receiverWalletId, new BigDecimal("500.0000"), "INR");
        senderProtocol.processPaymentRequest(req2, senderAlias);

        assertEquals(new BigDecimal("0.0000"), senderStorage.getRemainingBalance());

        // Third attempt exceeds balance
        PaymentRequest req3 = receiverProtocol.createPaymentRequest(receiverWalletId, new BigDecimal("50.0000"), "INR");
        assertThrows(IllegalStateException.class, () ->
                senderProtocol.processPaymentRequest(req3, senderAlias)
        );
    }

    @Test
    @DisplayName("Should reject receipt if challenge nonce does not match")
    void shouldRejectNonceMismatch() {
        PaymentRequest req1 = receiverProtocol.createPaymentRequest(receiverWalletId, new BigDecimal("100.0000"), "INR");
        PaymentRequest req2 = receiverProtocol.createPaymentRequest(receiverWalletId, new BigDecimal("100.0000"), "INR");

        // Sender signs response for req1
        PaymentResponse response = senderProtocol.processPaymentRequest(req1, senderAlias);

        // Receiver verifies against req2 (different nonce) -> must reject
        boolean accepted = receiverProtocol.verifyAndAcceptPayment(response, req2);
        assertFalse(accepted);
    }
}
