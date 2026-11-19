package com.payflow.api.dto.wallet;

import jakarta.validation.constraints.Pattern;

public record CreateWalletRequest(
        @Pattern(regexp = "^(INR|USD|EUR)$", message = "Supported currencies are INR, USD, EUR")
        String currency
) {}
