package com.payflow.api.dto.wallet;

import com.payflow.core.domain.ledger.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionHistoryResponse(
        UUID ledgerEntryId,
        UUID paymentId,
        String entryType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Instant timestamp
) {
    public static TransactionHistoryResponse from(LedgerEntry entry) {
        return new TransactionHistoryResponse(
                entry.getId(),
                entry.getPayment().getId(),
                entry.getEntryType().name(),
                entry.getAmount(),
                entry.getBalanceAfter(),
                entry.getCreatedAt()
        );
    }
}
