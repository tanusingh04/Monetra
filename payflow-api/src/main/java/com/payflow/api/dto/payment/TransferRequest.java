package com.payflow.api.dto.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotNull(message = "Sender wallet ID is required")
        UUID senderWalletId,

        @NotNull(message = "Receiver wallet ID is required")
        UUID receiverWalletId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Transfer amount must be at least 0.01")
        @Digits(integer = 15, fraction = 4, message = "Amount can have at most 4 decimal places")
        BigDecimal amount,

        @Pattern(regexp = "^(INR|USD|EUR)$", message = "Currency must be INR, USD, or EUR")
        String currency
) {}
