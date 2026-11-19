package com.payflow.core.domain.reputation;

import com.payflow.core.domain.user.User;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "user_reputation")
public class UserReputation {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @Column(nullable = false)
    private int score = 100;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReputationTier tier = ReputationTier.NEW;

    @Column(name = "clean_settled_count", nullable = false)
    private int cleanSettledCount = 0;

    @Column(name = "settled_amount_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal settledAmountTotal = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount = 0;

    @Column(name = "fraud_proofs_detected", nullable = false)
    private int fraudProofsDetected = 0;

    @Column(name = "fraud_proofs_confirmed", nullable = false)
    private int fraudProofsConfirmed = 0;

    @Column(nullable = false)
    private boolean restricted = false;

    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UserReputation() {}

    public UserReputation(User user, int initialScore) {
        this.user = user;
        this.userId = user.getId();
        this.score = Math.max(0, initialScore);
        this.tier = ReputationTier.NEW;
        this.cleanSettledCount = 0;
        this.settledAmountTotal = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        this.rejectedCount = 0;
        this.fraudProofsDetected = 0;
        this.fraudProofsConfirmed = 0;
        this.restricted = false;
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
        if (user != null) {
            this.userId = user.getId();
        }
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = Math.max(0, score);
    }

    public ReputationTier getTier() {
        return tier;
    }

    public void setTier(ReputationTier tier) {
        this.tier = tier;
    }

    public int getCleanSettledCount() {
        return cleanSettledCount;
    }

    public void setCleanSettledCount(int cleanSettledCount) {
        this.cleanSettledCount = cleanSettledCount;
    }

    public BigDecimal getSettledAmountTotal() {
        return settledAmountTotal;
    }

    public void setSettledAmountTotal(BigDecimal settledAmountTotal) {
        this.settledAmountTotal = settledAmountTotal != null
                ? settledAmountTotal.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public void setRejectedCount(int rejectedCount) {
        this.rejectedCount = rejectedCount;
    }

    public int getFraudProofsDetected() {
        return fraudProofsDetected;
    }

    public void setFraudProofsDetected(int fraudProofsDetected) {
        this.fraudProofsDetected = fraudProofsDetected;
    }

    public int getFraudProofsConfirmed() {
        return fraudProofsConfirmed;
    }

    public void setFraudProofsConfirmed(int fraudProofsConfirmed) {
        this.fraudProofsConfirmed = fraudProofsConfirmed;
    }

    public boolean isRestricted() {
        return restricted;
    }

    public void setRestricted(boolean restricted) {
        this.restricted = restricted;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserReputation that = (UserReputation) o;
        return Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }
}
