package com.payflow.core.domain.ledger;

import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.wallet.Wallet;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 10)
    private EntryType entryType;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public LedgerEntry() {}

    public LedgerEntry(Payment payment, Wallet wallet, EntryType entryType, BigDecimal amount, BigDecimal balanceAfter) {
        this.payment = payment;
        this.wallet = wallet;
        this.entryType = entryType;
        this.amount = amount != null ? amount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        this.balanceAfter = balanceAfter != null ? balanceAfter.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerEntry that = (LedgerEntry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
