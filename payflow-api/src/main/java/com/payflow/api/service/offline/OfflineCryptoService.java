package com.payflow.api.service.offline;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * High-performance, FIPS-compliant cryptographic service for the PayFlow Offline Wallet.
 * Implements ECDSA (NIST P-256 / secp256r1) with SHA-256 for authority certificate signing
 * and device transaction signature validation.
 */
@Service
public class OfflineCryptoService {

    private static final String EC_ALGORITHM = "EC";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final String CURVE_NAME = "secp256r1";

    private final KeyPair authorityKeyPair;

    public OfflineCryptoService() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(EC_ALGORITHM);
            ECGenParameterSpec ecSpec = new ECGenParameterSpec(CURVE_NAME);
            keyGen.initialize(ecSpec, new SecureRandom());
            this.authorityKeyPair = keyGen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize PayFlow Offline ECDSA Authority KeyPair", e);
        }
    }

    /**
     * Signs an offline allowance certificate with the backend authority private key.
     */
    public String signAllowanceCertificate(UUID allowanceId, UUID walletId, String deviceId,
                                           BigDecimal allocatedAmount, BigDecimal maxSingleTx, Instant expiresAt) {
        String payload = buildAllowancePayload(allowanceId, walletId, deviceId, allocatedAmount, maxSingleTx, expiresAt);
        return signData(payload.getBytes(StandardCharsets.UTF_8), authorityKeyPair.getPrivate());
    }

    /**
     * Verifies an offline transaction receipt signature using the sender device's enrolled public key.
     */
    public boolean verifyDeviceSignature(UUID txId, UUID allowanceId, UUID senderWalletId, UUID receiverWalletId,
                                         BigDecimal amount, String currency, long counter, String nonce,
                                         String signatureBase64, String devicePublicKeyBase64) {
        String payload = buildTransactionPayload(txId, allowanceId, senderWalletId, receiverWalletId, amount, currency, counter, nonce);
        return verifyDeviceSignature(payload, signatureBase64, devicePublicKeyBase64);
    }

    public boolean verifyDeviceSignature(String payload, String signatureBase64, String devicePublicKeyBase64) {
        try {
            PublicKey devicePublicKey = parsePublicKey(devicePublicKeyBase64);
            return verifyData(payload.getBytes(StandardCharsets.UTF_8), signatureBase64, devicePublicKey);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verifies an allowance certificate using the backend authority public key.
     */
    public boolean verifyAllowanceCertificate(UUID allowanceId, UUID walletId, String deviceId,
                                              BigDecimal allocatedAmount, BigDecimal maxSingleTx, Instant expiresAt,
                                              String signatureBase64) {
        try {
            String payload = buildAllowancePayload(allowanceId, walletId, deviceId, allocatedAmount, maxSingleTx, expiresAt);
            return verifyData(payload.getBytes(StandardCharsets.UTF_8), signatureBase64, authorityKeyPair.getPublic());
        } catch (Exception e) {
            return false;
        }
    }

    public String buildAllowancePayload(UUID allowanceId, UUID walletId, String deviceId,
                                        BigDecimal allocatedAmount, BigDecimal maxSingleTx, Instant expiresAt) {
        return String.format("%s|%s|%s|%s|%s|%d",
                allowanceId,
                walletId,
                deviceId,
                allocatedAmount.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                maxSingleTx.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                expiresAt.getEpochSecond()
        );
    }

    public String buildTransactionPayload(UUID txId, UUID allowanceId, UUID senderWalletId, UUID receiverWalletId,
                                          BigDecimal amount, String currency, long counter, String nonce) {
        return String.format("%s|%s|%s|%s|%s|%s|%d|%s",
                txId,
                allowanceId,
                senderWalletId,
                receiverWalletId,
                amount.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                currency.toUpperCase(),
                counter,
                nonce
        );
    }

    public String signData(byte[] data, PrivateKey privateKey) {
        try {
            Signature ecdsa = Signature.getInstance(SIGNATURE_ALGORITHM);
            ecdsa.initSign(privateKey);
            ecdsa.update(data);
            byte[] signature = ecdsa.sign();
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate ECDSA signature", e);
        }
    }

    public boolean verifyData(byte[] data, String signatureBase64, PublicKey publicKey) {
        try {
            Signature ecdsa = Signature.getInstance(SIGNATURE_ALGORITHM);
            ecdsa.initVerify(publicKey);
            ecdsa.update(data);
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            return ecdsa.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    public PublicKey parsePublicKey(String base64PublicKey) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
        return keyFactory.generatePublic(keySpec);
    }

    public String getAuthorityPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(authorityKeyPair.getPublic().getEncoded());
    }

    public PublicKey getAuthorityPublicKey() {
        return authorityKeyPair.getPublic();
    }
}
