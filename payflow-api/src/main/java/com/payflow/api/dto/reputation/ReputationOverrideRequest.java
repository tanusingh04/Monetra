package com.payflow.api.dto.reputation;

import com.payflow.core.domain.reputation.ReputationTier;
import jakarta.validation.constraints.NotBlank;

public record ReputationOverrideRequest(
        ReputationTier tier,
        Integer score,
        Boolean restricted,
        @NotBlank(message = "Override reason is mandatory for audit logging")
        String reason
) {}
