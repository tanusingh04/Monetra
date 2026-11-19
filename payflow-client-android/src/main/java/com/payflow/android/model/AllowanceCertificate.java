package com.payflow.android.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AllowanceCertificate(
        UUID allowanceId,
        UUID walletId,
        String deviceId,
        BigDecimal allocatedAmount,
        BigDecimal maxSingleTxAmount,
        Instant expiresAt,
        String serverSignature,
        String authorityPublicKey
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public String toSigningPayload() {
        return String.format("%s|%s|%s|%s|%s|%d",
                allowanceId,
                walletId,
                deviceId,
                allocatedAmount.toPlainString(),
                maxSingleTxAmount.toPlainString(),
                expiresAt.getEpochSecond()
        );
    }
}
