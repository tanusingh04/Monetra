package com.payflow.core.domain.offline;

import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.wallet.Wallet;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "offline_transactions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_offline_tx_allowance_counter", columnNames = {"allowance_id", "counter"})
})
public class OfflineTransaction {

    @Id
    private UUID id; // Client-generated UUID

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "allowance_id", nullable = false)
    private OfflineAllowance allowance;

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

    @Column(nullable = false)
    private Long counter;

    @Column(nullable = false, length = 64)
    private String nonce;

    @Column(name = "device_signature", nullable = false, columnDefinition = "TEXT")
    private String deviceSignature;

    @Column(name = "receipt_payload", columnDefinition = "TEXT")
    private String receiptPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OfflineTransactionStatus status = OfflineTransactionStatus.PENDING;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_category", length = 30)
    private RejectionCategory rejectionCategory;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "synchronized_at", nullable = false)
    private Instant synchronizedAt = Instant.now();

    public OfflineTransaction() {}

    public OfflineTransaction(UUID id, OfflineAllowance allowance, Wallet senderWallet, Wallet receiverWallet,
                              BigDecimal amount, String currency, Long counter, String nonce,
                              String deviceSignature, Instant createdAt) {
        this(id, allowance, senderWallet, receiverWallet, amount, currency, counter, nonce, deviceSignature, null, createdAt);
    }

    public OfflineTransaction(UUID id, OfflineAllowance allowance, Wallet senderWallet, Wallet receiverWallet,
                              BigDecimal amount, String currency, Long counter, String nonce,
                              String deviceSignature, String receiptPayload, Instant createdAt) {
        this.id = id;
        this.allowance = allowance;
        this.senderWallet = senderWallet;
        this.receiverWallet = receiverWallet;
        this.amount = amount != null ? amount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        this.currency = currency != null ? currency.toUpperCase() : "INR";
        this.counter = counter;
        this.nonce = nonce;
        this.deviceSignature = deviceSignature;
        this.receiptPayload = receiptPayload;
        this.status = OfflineTransactionStatus.PENDING;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.synchronizedAt = Instant.now();
    }

    public void markSettled(Payment payment) {
        this.status = OfflineTransactionStatus.SETTLED;
        this.payment = payment;
    }

    public void markRejected(String reason) {
        markRejected(reason, RejectionCategory.SENDER_ATTRIBUTABLE);
    }

    public void markRejected(String reason, RejectionCategory category) {
        this.status = OfflineTransactionStatus.REJECTED;
        this.rejectionReason = reason;
        this.rejectionCategory = category;
    }

    public RejectionCategory getRejectionCategory() {
        return rejectionCategory;
    }

    public UUID getId() {
        return id;
    }

    public OfflineAllowance getAllowance() {
        return allowance;
    }

    public Wallet getSenderWallet() {
        return senderWallet;
    }

    public Wallet getReceiverWallet() {
        return receiverWallet;
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

    public String getNonce() {
        return nonce;
    }

    public String getDeviceSignature() {
        return deviceSignature;
    }

    public String getReceiptPayload() {
        return receiptPayload;
    }

    public void setReceiptPayload(String receiptPayload) {
        this.receiptPayload = receiptPayload;
    }

    public OfflineTransactionStatus getStatus() {
        return status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Payment getPayment() {
        return payment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSynchronizedAt() {
        return synchronizedAt;
    }
}
