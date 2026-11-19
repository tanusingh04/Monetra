package com.payflow.api.dto.reputation;

import com.payflow.core.domain.reputation.ReputationTier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReputationResponse(
        UUID userId,
        int score,
        ReputationTier tier,
        BigDecimal maxAllowanceLimit,
        BigDecimal maxSingleTxLimit,
        int cleanSettledCount,
        BigDecimal settledAmountTotal,
        int rejectedCount,
        int fraudProofsDetected,
        int fraudProofsConfirmed,
        boolean restricted,
        NextTierProgress nextTierProgress,
        List<ReputationEventResponse> recentEvents,
        Instant updatedAt
) {}
