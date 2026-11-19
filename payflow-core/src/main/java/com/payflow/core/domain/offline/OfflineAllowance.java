package com.payflow.core.domain.offline;

import com.payflow.core.domain.user.User;
import com.payflow.core.domain.wallet.Wallet;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "offline_allowances")
public class OfflineAllowance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedAmount;

    @Column(name = "spent_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal spentAmount = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

    @Column(name = "max_single_tx_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxSingleTxAmount;

    @Column(name = "last_counter", nullable = false)
    private Long lastCounter = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AllowanceStatus status = AllowanceStatus.ACTIVE;

    @Column(name = "server_signature", nullable = false, columnDefinition = "TEXT")
    private String serverSignature;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 30)
    private RevocationReason revocationReason;

    public OfflineAllowance() {}

    public OfflineAllowance(User user, Wallet wallet, String deviceId, BigDecimal allocatedAmount,
                            BigDecimal maxSingleTxAmount, Instant expiresAt) {
        this.user = user;
        this.wallet = wallet;
        this.deviceId = deviceId;
        this.allocatedAmount = allocatedAmount.setScale(4, RoundingMode.HALF_UP);
        this.spentAmount = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        this.maxSingleTxAmount = maxSingleTxAmount.setScale(4, RoundingMode.HALF_UP);
        this.expiresAt = expiresAt;
        this.status = AllowanceStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public BigDecimal getRemainingAllowance() {
        return this.allocatedAmount.subtract(this.spentAmount).setScale(4, RoundingMode.HALF_UP);
    }

    public void recordSpent(BigDecimal amount, long counter) {
        BigDecimal scaled = amount.setScale(4, RoundingMode.HALF_UP);
        this.spentAmount = this.spentAmount.add(scaled);
        if (counter > this.lastCounter) {
            this.lastCounter = counter;
        }
    }

    public void markSettled() {
        this.status = AllowanceStatus.SETTLED;
        this.settledAt = Instant.now();
    }

    public void markExpired() {
        this.status = AllowanceStatus.EXPIRED;
        this.settledAt = Instant.now();
    }

    public void markRevoked() {
        markRevoked(RevocationReason.OTHER);
    }

    public void markRevoked(RevocationReason reason) {
        this.status = AllowanceStatus.REVOKED;
        this.settledAt = Instant.now();
        this.revocationReason = reason;
    }

    public RevocationReason getRevocationReason() {
        return revocationReason;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(this.expiresAt);
    }

    public boolean isActive() {
        return this.status == AllowanceStatus.ACTIVE && !isExpired();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public BigDecimal getAllocatedAmount() {
        return allocatedAmount;
    }

    public BigDecimal getSpentAmount() {
        return spentAmount;
    }

    public BigDecimal getMaxSingleTxAmount() {
        return maxSingleTxAmount;
    }

    public Long getLastCounter() {
        return lastCounter;
    }

    public AllowanceStatus getStatus() {
        return status;
    }

    public void setStatus(AllowanceStatus status) {
        this.status = status;
    }

    public String getServerSignature() {
        return serverSignature;
    }

    public void setServerSignature(String serverSignature) {
        this.serverSignature = serverSignature;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}
