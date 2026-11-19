package com.payflow.android.fraud;

import com.payflow.android.crypto.SecureKeyStore;
import com.payflow.android.crypto.StandardSecureKeyStore;
import com.payflow.android.model.PaymentReceipt;

import java.nio.charset.StandardCharsets;

public class OfflineFraudProofVerifier {

    private final SecureKeyStore keyStore;

    public OfflineFraudProofVerifier() {
        this(new StandardSecureKeyStore());
    }

    public OfflineFraudProofVerifier(SecureKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    public record FraudProofCheckResult(
            boolean isFraudProof,
            String reason,
            boolean firstSignatureValid,
            boolean secondSignatureValid
    ) {}

    /**
     * Checks locally whether two payment receipts constitute a double-spend fraud proof.
     * A valid fraud proof requires:
     * 1. Same allowanceId
     * 2. Same transaction counter
     * 3. Different signing payloads (different txId, receiver, or amount)
     * 4. BOTH signatures valid under the device public key (anti-framing protection)
     */
    public FraudProofCheckResult verifyFraudProof(PaymentReceipt r1, PaymentReceipt r2, String devicePublicKeyBase64) {
        if (r1 == null || r2 == null) {
            return new FraudProofCheckResult(false, "Both receipts must be provided", false, false);
        }

        if (devicePublicKeyBase64 == null || devicePublicKeyBase64.isBlank()) {
            return new FraudProofCheckResult(false, "Device public key must be provided", false, false);
        }

        if (!r1.allowanceId().equals(r2.allowanceId())) {
            return new FraudProofCheckResult(false, "Receipts belong to different allowances", false, false);
        }

        if (r1.counter() != r2.counter()) {
            return new FraudProofCheckResult(false, "Counters do not match: " + r1.counter() + " vs " + r2.counter(), false, false);
        }

        String payload1 = r1.toSigningPayload();
        String payload2 = r2.toSigningPayload();

        if (payload1.equals(payload2) || r1.txId().equals(r2.txId())) {
            return new FraudProofCheckResult(false, "Identical receipt resubmission; not an equivocation", false, false);
        }

        boolean sig1Valid = keyStore.verify(payload1.getBytes(StandardCharsets.UTF_8), r1.deviceSignature(), devicePublicKeyBase64);
        boolean sig2Valid = keyStore.verify(payload2.getBytes(StandardCharsets.UTF_8), r2.deviceSignature(), devicePublicKeyBase64);

        if (!sig1Valid || !sig2Valid) {
            return new FraudProofCheckResult(false,
                    "Invalid cryptographic signature detected. Anti-framing protection triggered.",
                    sig1Valid, sig2Valid);
        }

        return new FraudProofCheckResult(true,
                "Offline double-spend equivocation fraud proof confirmed",
                true, true);
    }
}
