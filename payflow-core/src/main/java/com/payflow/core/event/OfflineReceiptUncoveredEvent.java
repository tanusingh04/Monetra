package com.payflow.core.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OfflineReceiptUncoveredEvent(
        UUID eventId,
        UUID proofId,
        UUID txId,
        UUID receiverWalletId,
        UUID senderWalletId,
        BigDecimal amount,
        BigDecimal uncoveredAmount,
        String reason,
        Instant timestamp
) {}
