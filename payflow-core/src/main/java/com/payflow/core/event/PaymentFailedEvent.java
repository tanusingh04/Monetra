package com.payflow.core.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentFailedEvent(
    UUID eventId,
    UUID paymentId,
    String idempotencyKey,
    UUID senderWalletId,
    UUID receiverWalletId,
    BigDecimal amount,
    String currency,
    String failureReason,
    Instant timestamp
) {}
