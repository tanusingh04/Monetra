package com.payflow.api.dto.reputation;

import com.payflow.core.domain.reputation.ReputationTier;

public record NextTierProgress(
        ReputationTier nextTier,
        int scoreNeeded,
        int daysRemaining,
        long counterpartiesRemaining
) {}
