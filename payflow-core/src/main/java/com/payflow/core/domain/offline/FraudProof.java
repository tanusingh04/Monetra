package com.payflow.core.domain.offline;

import com.payflow.core.domain.wallet.Wallet;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "offline_fraud_proofs", uniqueConstraints = {
        @UniqueConstraint(name = "uq_offline_fraud_proofs_equivocation",
                columnNames = {"allowance_id", "counter", "first_tx_id", "second_tx_id"})
})
public class FraudProof {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "allowance_id", nullable = false)
    private OfflineAllowance allowance;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(nullable = false)
    private Long counter;

    @Column(name = "first_tx_id", nullable = false)
    private UUID firstTxId;

    @Column(name = "second_tx_id", nullable = false)
    private UUID secondTxId;

    @Column(name = "first_receipt_payload", nullable = false, columnDefinition = "TEXT")
    private String firstReceiptPayload;

    @Column(name = "first_signature", nullable = false, columnDefinition = "TEXT")
    private String firstSignature;

    @Column(name = "second_receipt_payload", nullable = false, columnDefinition = "TEXT")
    private String secondReceiptPayload;

    @Column(name = "second_signature", nullable = false, columnDefinition = "TEXT")
    private String secondSignature;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FraudProofStatus status = FraudProofStatus.OPEN;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt = Instant.now();

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "second_receipt_settled", nullable = false)
    private Boolean secondReceiptSettled = false;

    @Column(name = "protected_amount", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal protectedAmount = java.math.BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP);

    @Column(name = "uncovered_amount", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal uncoveredAmount = java.math.BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP);

    @Column(name = "settlement_payment_id")
    private UUID settlementPaymentId;

    public FraudProof() {}

    public FraudProof(OfflineAllowance allowance, String deviceId, Wallet wallet, Long counter,
                      UUID firstTxId, UUID secondTxId,
                      String firstReceiptPayload, String firstSignature,
                      String secondReceiptPayload, String secondSignature) {
        this.id = UUID.randomUUID();
        this.allowance = allowance;
        this.deviceId = deviceId;
        this.wallet = wallet;
        this.counter = counter;
        this.firstTxId = firstTxId;
        this.secondTxId = secondTxId;
        this.firstReceiptPayload = firstReceiptPayload;
        this.firstSignature = firstSignature;
        this.secondReceiptPayload = secondReceiptPayload;
        this.secondSignature = secondSignature;
        this.status = FraudProofStatus.OPEN;
        this.detectedAt = Instant.now();
    }

    public void review(FraudProofStatus newStatus, String reviewer, String note) {
        if (newStatus != FraudProofStatus.CONFIRMED && newStatus != FraudProofStatus.DISMISSED && newStatus != FraudProofStatus.UNDER_REVIEW) {
            throw new IllegalArgumentException("Invalid review status: " + newStatus);
        }
        this.status = newStatus;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.reviewNote = note;
    }

    public UUID getId() {
        return id;
    }

    public OfflineAllowance getAllowance() {
        return allowance;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public Long getCounter() {
        return counter;
    }

    public UUID getFirstTxId() {
        return firstTxId;
    }

    public UUID getSecondTxId() {
        return secondTxId;
    }

    public String getFirstReceiptPayload() {
        return firstReceiptPayload;
    }

    public String getFirstSignature() {
        return firstSignature;
    }

    public String getSecondReceiptPayload() {
        return secondReceiptPayload;
    }

    public String getSecondSignature() {
        return secondSignature;
    }

    public FraudProofStatus getStatus() {
        return status;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void recordProtectionOutcome(boolean settled, java.math.BigDecimal protectedAmt, java.math.BigDecimal uncoveredAmt, UUID paymentId) {
        this.secondReceiptSettled = settled;
        this.protectedAmount = protectedAmt != null ? protectedAmt.setScale(4, java.math.RoundingMode.HALF_UP) : java.math.BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP);
        this.uncoveredAmount = uncoveredAmt != null ? uncoveredAmt.setScale(4, java.math.RoundingMode.HALF_UP) : java.math.BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP);
        this.settlementPaymentId = paymentId;
    }

    public Boolean getSecondReceiptSettled() {
        return secondReceiptSettled;
    }

    public java.math.BigDecimal getProtectedAmount() {
        return protectedAmount;
    }

    public java.math.BigDecimal getUncoveredAmount() {
        return uncoveredAmount;
    }

    public UUID getSettlementPaymentId() {
        return settlementPaymentId;
    }
}
