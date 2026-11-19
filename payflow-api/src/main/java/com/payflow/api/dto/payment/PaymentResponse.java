package com.payflow.api.dto.payment;

import com.payflow.core.domain.payment.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        String idempotencyKey,
        UUID senderWalletId,
        UUID receiverWalletId,
        BigDecimal amount,
        String currency,
        String status,
        String failureReason,
        Instant createdAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getIdempotencyKey(),
                payment.getSenderWallet().getId(),
                payment.getReceiverWallet().getId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getFailureReason(),
                payment.getCreatedAt()
        );
    }
}
