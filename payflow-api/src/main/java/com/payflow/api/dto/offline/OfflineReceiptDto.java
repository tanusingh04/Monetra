package com.payflow.api.dto.offline;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OfflineReceiptDto(
        @NotNull(message = "Transaction ID is required")
        UUID txId,

        @NotNull(message = "Allowance ID is required")
        UUID allowanceId,

        @NotNull(message = "Sender wallet ID is required")
        UUID senderWalletId,

        @NotNull(message = "Receiver wallet ID is required")
        UUID receiverWalletId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be strictly positive")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        String currency,

        @NotNull(message = "Monotonic counter is required")
        Long counter,

        @NotBlank(message = "Nonce is required")
        String nonce,

        @NotBlank(message = "Device signature is required")
        String deviceSignature,

        @NotNull(message = "Creation timestamp is required")
        Instant createdAt
) {}
