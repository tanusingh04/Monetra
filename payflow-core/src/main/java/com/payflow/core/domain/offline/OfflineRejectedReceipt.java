package com.payflow.core.domain.offline;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "offline_rejected_receipts")
public class OfflineRejectedReceipt {

    @Id
    @Column(name = "tx_id")
    private UUID txId;

    @Column(name = "allowance_id")
    private UUID allowanceId;

    @Column(name = "sender_wallet_id")
    private UUID senderWalletId;

    @Column(name = "receiver_wallet_id")
    private UUID receiverWalletId;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency = "INR";

    @Column
    private Long counter;

    @Column(name = "rejection_reason", nullable = false, columnDefinition = "TEXT")
    private String rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RejectionCategory category = RejectionCategory.SENDER_ATTRIBUTABLE;

    @Column(name = "rejected_at", nullable = false, updatable = false)
    private Instant rejectedAt = Instant.now();

    public OfflineRejectedReceipt() {}

    public OfflineRejectedReceipt(UUID txId, UUID allowanceId, UUID senderWalletId, UUID receiverWalletId,
                                  BigDecimal amount, String currency, Long counter,
                                  String rejectionReason, RejectionCategory category) {
        this.txId = txId;
        this.allowanceId = allowanceId;
        this.senderWalletId = senderWalletId;
        this.receiverWalletId = receiverWalletId;
        this.amount = amount != null ? amount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        this.currency = currency != null ? currency.toUpperCase() : "INR";
        this.counter = counter;
        this.rejectionReason = rejectionReason;
        this.category = category != null ? category : RejectionCategory.SENDER_ATTRIBUTABLE;
        this.rejectedAt = Instant.now();
    }

    public UUID getTxId() {
        return txId;
    }

    public UUID getAllowanceId() {
        return allowanceId;
    }

    public UUID getSenderWalletId() {
        return senderWalletId;
    }

    public UUID getReceiverWalletId() {
        return receiverWalletId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getCounter() {
        return counter;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public RejectionCategory getCategory() {
        return category;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }
}
