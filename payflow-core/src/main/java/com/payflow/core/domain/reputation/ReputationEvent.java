package com.payflow.core.domain.reputation;

import com.payflow.core.domain.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "reputation_events")
public class ReputationEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private ReputationEventType eventType;

    @Column(name = "score_delta", nullable = false)
    private int scoreDelta;

    @Column(name = "score_after", nullable = false)
    private int scoreAfter;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "ref_id", length = 100)
    private String refId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ReputationEvent() {}

    public ReputationEvent(User user,
                           ReputationEventType eventType,
                           int scoreDelta,
                           int scoreAfter,
                           String reason,
                           String refId) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.eventType = eventType;
        this.scoreDelta = scoreDelta;
        this.scoreAfter = scoreAfter;
        this.reason = reason;
        this.refId = refId;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public ReputationEventType getEventType() {
        return eventType;
    }

    public int getScoreDelta() {
        return scoreDelta;
    }

    public int getScoreAfter() {
        return scoreAfter;
    }

    public String getReason() {
        return reason;
    }

    public String getRefId() {
        return refId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReputationEvent that = (ReputationEvent) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
