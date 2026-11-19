package com.payflow.core.event;

import com.payflow.core.domain.reputation.ReputationTier;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event emitted when a user's reputation tier transitions.
 */
public record ReputationTierChangedEvent(
        UUID eventId,
        UUID userId,
        ReputationTier previousTier,
        ReputationTier newTier,
        int score,
        Instant timestamp
) {}
