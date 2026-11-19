package com.payflow.api.dto.offline;

import com.payflow.core.domain.offline.FraudProofStatus;
import jakarta.validation.constraints.NotNull;

public record ReviewFraudProofRequest(
        @NotNull(message = "Review decision is required (CONFIRMED or DISMISSED)")
        FraudProofStatus decision,

        String note
) {}
