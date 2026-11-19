package com.payflow.android.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record PaymentReceipt(
        UUID txId,
        UUID allowanceId,
        UUID senderWalletId,
        UUID receiverWalletId,
        BigDecimal amount,
        String currency,
        long counter,
        String nonce,
        String deviceSignature,
        Instant createdAt
) {
    public String toSigningPayload() {
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
}
