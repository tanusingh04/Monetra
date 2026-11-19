package com.payflow.api.dto.reputation;

import com.payflow.core.domain.reputation.ReputationEvent;
import com.payflow.core.domain.reputation.ReputationEventType;

import java.time.Instant;
import java.util.UUID;

public record ReputationEventResponse(
        UUID id,
        ReputationEventType eventType,
        int scoreDelta,
        int scoreAfter,
        String reason,
        String refId,
        Instant createdAt
) {
    public static ReputationEventResponse from(ReputationEvent event) {
        return new ReputationEventResponse(
                event.getId(),
                event.getEventType(),
                event.getScoreDelta(),
                event.getScoreAfter(),
                event.getReason(),
                event.getRefId(),
                event.getCreatedAt()
        );
    }
}
