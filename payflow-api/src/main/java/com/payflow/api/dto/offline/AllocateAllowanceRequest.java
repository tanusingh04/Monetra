package com.payflow.api.dto.offline;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record AllocateAllowanceRequest(
        @NotNull(message = "Wallet ID is required")
        UUID walletId,

        @NotBlank(message = "Device ID is required")
        String deviceId,

        @NotNull(message = "Allocated amount is required")
        @DecimalMin(value = "1.00", message = "Minimum allowance is 1.00")
        BigDecimal allocatedAmount,

        @NotNull(message = "Maximum single transaction amount is required")
        @DecimalMin(value = "1.00", message = "Minimum single transaction amount is 1.00")
        BigDecimal maxSingleTxAmount,

        @Min(value = 1, message = "Minimum duration is 1 hour")
        @Max(value = 72, message = "Maximum duration is 72 hours")
        Integer durationHours
) {}
