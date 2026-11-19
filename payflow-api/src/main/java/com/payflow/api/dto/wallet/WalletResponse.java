package com.payflow.api.dto.wallet;

import com.payflow.core.domain.wallet.Wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID walletId,
        UUID userId,
        String currency,
        BigDecimal balance,
        String status,
        Instant createdAt
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getUser().getId(),
                wallet.getCurrency(),
                wallet.getBalance(),
                wallet.getStatus().name(),
                wallet.getCreatedAt()
        );
    }
}
