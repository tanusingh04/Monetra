package com.payflow.core.domain.payment;

import com.payflow.core.domain.wallet.Wallet;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_wallet_id", nullable = false)
    private Wallet senderWallet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_wallet_id", nullable = false)
    private Wallet receiverWallet;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Payment() {}

    public Payment(String idempotencyKey, Wallet senderWallet, Wallet receiverWallet, BigDecimal amount, String currency) {
        this.idempotencyKey = idempotencyKey;
        this.senderWallet = senderWallet;
        this.receiverWallet = receiverWallet;
        this.amount = amount != null ? amount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        this.currency = currency != null ? currency.toUpperCase() : "INR";
        this.status = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void markCompleted() {
        this.status = PaymentStatus.COMPLETED;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Wallet getSenderWallet() {
        return senderWallet;
    }

    public void setSenderWallet(Wallet senderWallet) {
        this.senderWallet = senderWallet;
    }

    public Wallet getReceiverWallet() {
        return receiverWallet;
    }

    public void setReceiverWallet(Wallet receiverWallet) {
        this.receiverWallet = receiverWallet;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount != null ? amount.setScale(4, RoundingMode.HALF_UP) : null;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Payment payment = (Payment) o;
        return Objects.equals(id, payment.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
