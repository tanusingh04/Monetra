package com.payflow.api.service.offline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OfflineCryptoServiceTest {

    private OfflineCryptoService cryptoService;
    private KeyPair deviceKeyPair;
    private String devicePublicKeyBase64;

    @BeforeEach
    void setUp() throws Exception {
        cryptoService = new OfflineCryptoService();

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        deviceKeyPair = keyGen.generateKeyPair();
        devicePublicKeyBase64 = Base64.getEncoder().encodeToString(deviceKeyPair.getPublic().getEncoded());
    }

    @Test
    @DisplayName("Should sign and successfully verify authority allowance certificate")
    void shouldSignAndVerifyAllowanceCertificate() {
        UUID allowanceId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        String deviceId = "android-device-1";
        BigDecimal allocated = new BigDecimal("2000.0000");
        BigDecimal maxSingle = new BigDecimal("500.0000");
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));

        String signature = cryptoService.signAllowanceCertificate(
                allowanceId, walletId, deviceId, allocated, maxSingle, expiresAt);

        assertNotNull(signature);
        boolean valid = cryptoService.verifyAllowanceCertificate(
                allowanceId, walletId, deviceId, allocated, maxSingle, expiresAt, signature);

        assertTrue(valid);
    }

    @Test
    @DisplayName("Should verify valid device transaction signature and reject tampered payload")
    void shouldVerifyDeviceSignatureAndRejectTampering() {
        UUID txId = UUID.randomUUID();
        UUID allowanceId = UUID.randomUUID();
        UUID senderWalletId = UUID.randomUUID();
        UUID receiverWalletId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("150.0000");
        String currency = "INR";
        long counter = 1L;
        String nonce = "a1b2c3d4e5f6";

        String payload = cryptoService.buildTransactionPayload(
                txId, allowanceId, senderWalletId, receiverWalletId, amount, currency, counter, nonce);

        String signature = cryptoService.signData(payload.getBytes(), deviceKeyPair.getPrivate());

        // Verify valid signature
        boolean valid = cryptoService.verifyDeviceSignature(
                txId, allowanceId, senderWalletId, receiverWalletId, amount, currency, counter, nonce,
                signature, devicePublicKeyBase64);
        assertTrue(valid);

        // Verify tampering with amount fails
        BigDecimal tamperedAmount = new BigDecimal("150.0100");
        boolean tamperedValid = cryptoService.verifyDeviceSignature(
                txId, allowanceId, senderWalletId, receiverWalletId, tamperedAmount, currency, counter, nonce,
                signature, devicePublicKeyBase64);
        assertFalse(tamperedValid);

        // Verify tampering with counter fails
        boolean tamperedCounterValid = cryptoService.verifyDeviceSignature(
                txId, allowanceId, senderWalletId, receiverWalletId, amount, currency, 2L, nonce,
                signature, devicePublicKeyBase64);
        assertFalse(tamperedCounterValid);
    }
}
