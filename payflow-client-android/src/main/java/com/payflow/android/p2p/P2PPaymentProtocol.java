package com.payflow.android.p2p;

import com.payflow.android.crypto.SecureKeyStore;
import com.payflow.android.model.AllowanceCertificate;
import com.payflow.android.model.PaymentReceipt;
import com.payflow.android.model.PaymentRequest;
import com.payflow.android.model.PaymentResponse;
import com.payflow.android.storage.EncryptedOfflineStorage;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * End-to-end peer-to-peer payment protocol executing offline transactions
 * over NFC (Host Card Emulation) and Bluetooth Low Energy (BLE).
 */
public class P2PPaymentProtocol {

    private final SecureKeyStore keyStore;
    private final EncryptedOfflineStorage storage;
    private final SecureRandom secureRandom = new SecureRandom();

    public P2PPaymentProtocol(SecureKeyStore keyStore, EncryptedOfflineStorage storage) {
        this.keyStore = keyStore;
        this.storage = storage;
    }

    /**
     * Receiver generates an offline payment request with a cryptographic challenge nonce.
     */
    public PaymentRequest createPaymentRequest(UUID receiverWalletId, BigDecimal amount, String currency) {
        byte[] nonceBytes = new byte[16];
        secureRandom.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        return new PaymentRequest(receiverWalletId, amount, currency, nonce);
    }

    /**
     * Sender verifies local allowance constraints, increments monotonic counter,
     * signs transaction receipt using hardware key, and updates local available balance.
     */
    public PaymentResponse processPaymentRequest(PaymentRequest request, String keyAlias) {
        Optional<AllowanceCertificate> allowanceOpt = storage.getActiveAllowance();
        if (allowanceOpt.isEmpty()) {
            throw new IllegalStateException("No active or non-expired offline allowance available on device");
        }

        AllowanceCertificate allowance = allowanceOpt.get();

        if (request.amount().compareTo(allowance.maxSingleTxAmount()) > 0) {
            throw new IllegalArgumentException("Requested amount " + request.amount() + " exceeds single transaction limit " + allowance.maxSingleTxAmount());
        }

        BigDecimal remaining = storage.getRemainingBalance();
        if (request.amount().compareTo(remaining) > 0) {
            throw new IllegalStateException("Insufficient offline balance. Remaining: " + remaining + ", requested: " + request.amount());
        }

        long counter = storage.getNextCounter();
        UUID txId = UUID.randomUUID();

        PaymentReceipt receipt = new PaymentReceipt(
                txId,
                allowance.allowanceId(),
                allowance.walletId(),
                request.receiverWalletId(),
                request.amount(),
                request.currency(),
                counter,
                request.nonce(),
                null,
                Instant.now()
        );

        String payload = receipt.toSigningPayload();
        String signature = keyStore.sign(keyAlias, payload.getBytes(StandardCharsets.UTF_8));

        PaymentReceipt signedReceipt = new PaymentReceipt(
                receipt.txId(),
                receipt.allowanceId(),
                receipt.senderWalletId(),
                receipt.receiverWalletId(),
                receipt.amount(),
                receipt.currency(),
                receipt.counter(),
                receipt.nonce(),
                signature,
                receipt.createdAt()
        );

        // Deduct from local offline allowance
        storage.recordSpend(request.amount());

        return new PaymentResponse(signedReceipt, allowance, keyStore.getPublicKeyBase64(keyAlias));
    }

    /**
     * Receiver verifies PayFlow CA signature on allowance certificate, verifies sender's signature,
     * validates challenge nonce freshness, and persists receipt for backend synchronization.
     */
    public boolean verifyAndAcceptPayment(PaymentResponse response, PaymentRequest originalRequest) {
        PaymentReceipt receipt = response.receipt();
        AllowanceCertificate cert = response.certificate();

        // 1. Validate Nonce Match (Prevents replay of past transactions)
        if (!originalRequest.nonce().equals(receipt.nonce())) {
            return false;
        }

        // 2. Validate Amount and Receiver ID
        if (originalRequest.amount().compareTo(receipt.amount()) != 0 ||
            !originalRequest.receiverWalletId().equals(receipt.receiverWalletId())) {
            return false;
        }

        // 3. Validate Allowance Expiration
        if (cert.isExpired()) {
            return false;
        }

        // 4. Verify Backend Authority Signature on Allowance Certificate
        boolean validCert = keyStore.verify(
                cert.toSigningPayload().getBytes(StandardCharsets.UTF_8),
                cert.serverSignature(),
                cert.authorityPublicKey()
        );
        if (!validCert) {
            return false;
        }

        // 5. Verify Sender Device ECDSA Signature on Receipt
        boolean validSignature = keyStore.verify(
                receipt.toSigningPayload().getBytes(StandardCharsets.UTF_8),
                receipt.deviceSignature(),
                response.senderPublicKey()
        );
        if (!validSignature) {
            return false;
        }

        // 6. Store accepted receipt in local receiver storage for later backend synchronization
        storage.saveReceivedReceipt(receipt);
        return true;
    }
}
