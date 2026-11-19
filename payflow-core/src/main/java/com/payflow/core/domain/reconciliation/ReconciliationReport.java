package com.payflow.core.domain.reconciliation;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_reports")
public class ReconciliationReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReconciliationStatus status;

    @Column(name = "total_wallets_checked", nullable = false)
    private int totalWalletsChecked;

    @Column(name = "mismatched_wallets_count", nullable = false)
    private int mismatchedWalletsCount;

    @Column(name = "total_ledger_debits", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalLedgerDebits;

    @Column(name = "total_ledger_credits", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalLedgerCredits;

    @Column(name = "discrepancy_details", columnDefinition = "TEXT")
    private String discrepancyDetails;

    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt = Instant.now();

    public ReconciliationReport() {}

    public ReconciliationReport(ReconciliationStatus status, int totalWalletsChecked, int mismatchedWalletsCount,
                                BigDecimal totalLedgerDebits, BigDecimal totalLedgerCredits, String discrepancyDetails) {
        this.status = status;
        this.totalWalletsChecked = totalWalletsChecked;
        this.mismatchedWalletsCount = mismatchedWalletsCount;
        this.totalLedgerDebits = totalLedgerDebits != null ? totalLedgerDebits.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        this.totalLedgerCredits = totalLedgerCredits != null ? totalLedgerCredits.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        this.discrepancyDetails = discrepancyDetails;
        this.executedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public int getTotalWalletsChecked() {
        return totalWalletsChecked;
    }

    public int getMismatchedWalletsCount() {
        return mismatchedWalletsCount;
    }

    public BigDecimal getTotalLedgerDebits() {
        return totalLedgerDebits;
    }

    public BigDecimal getTotalLedgerCredits() {
        return totalLedgerCredits;
    }

    public String getDiscrepancyDetails() {
        return discrepancyDetails;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReconciliationReport that = (ReconciliationReport) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
