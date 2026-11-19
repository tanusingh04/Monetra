package com.payflow.core.domain.offline;

import com.payflow.core.domain.payment.Payment;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "offline_protected_settlements")
public class OfflineProtectedSettlement {

    @Id
    @Column(name = "tx_id")
    private UUID txId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proof_id", nullable = false)
    private FraudProof fraudProof;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "receipt_payload", nullable = false, columnDefinition = "TEXT")
    private String receiptPayload;

    @Column(name = "device_signature", nullable = false, columnDefinition = "TEXT")
    private String deviceSignature;

    @Column(name = "settled_at", nullable = false, updatable = false)
    private Instant settledAt = Instant.now();

    public OfflineProtectedSettlement() {}

    public OfflineProtectedSettlement(UUID txId, FraudProof fraudProof, Payment payment,
                                     BigDecimal amount, String receiptPayload, String deviceSignature) {
        this.txId = txId;
        this.fraudProof = fraudProof;
        this.payment = payment;
        this.amount = amount != null ? amount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        this.receiptPayload = receiptPayload;
        this.deviceSignature = deviceSignature;
        this.settledAt = Instant.now();
    }

    public UUID getTxId() {
        return txId;
    }

    public FraudProof getFraudProof() {
        return fraudProof;
    }

    public Payment getPayment() {
        return payment;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReceiptPayload() {
        return receiptPayload;
    }

    public String getDeviceSignature() {
        return deviceSignature;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}
