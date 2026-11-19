package com.payflow.core.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OfflineFraudProofDetectedEvent(
        UUID eventId,
        UUID proofId,
        UUID allowanceId,
        UUID walletId,
        String deviceId,
        Long counter,
        UUID firstTxId,
        UUID secondTxId,
        Boolean secondReceiptSettled,
        BigDecimal protectedAmount,
        BigDecimal uncoveredAmount,
        Instant timestamp
) {
    public OfflineFraudProofDetectedEvent(
            UUID eventId,
            UUID proofId,
            UUID allowanceId,
            UUID walletId,
            String deviceId,
            Long counter,
            UUID firstTxId,
            UUID secondTxId,
            Instant timestamp
    ) {
        this(eventId, proofId, allowanceId, walletId, deviceId, counter, firstTxId, secondTxId,
                false, BigDecimal.ZERO, BigDecimal.ZERO, timestamp);
    }
}
