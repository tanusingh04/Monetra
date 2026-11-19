package com.payflow.android.model;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentRequest(
        UUID receiverWalletId,
        BigDecimal amount,
        String currency,
        String nonce
) {}
