package com.payflow.api.dto.reconciliation;

import com.payflow.core.domain.reconciliation.ReconciliationReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReconciliationReportResponse(
        UUID reportId,
        String status,
        int totalWalletsChecked,
        int mismatchedWalletsCount,
        BigDecimal totalLedgerDebits,
        BigDecimal totalLedgerCredits,
        BigDecimal netLedgerParityDifference,
        String discrepancyDetails,
        Instant executedAt
) {
    public static ReconciliationReportResponse from(ReconciliationReport report) {
        BigDecimal diff = report.getTotalLedgerDebits().subtract(report.getTotalLedgerCredits()).abs();
        return new ReconciliationReportResponse(
                report.getId(),
                report.getStatus().name(),
                report.getTotalWalletsChecked(),
                report.getMismatchedWalletsCount(),
                report.getTotalLedgerDebits(),
                report.getTotalLedgerCredits(),
                diff,
                report.getDiscrepancyDetails(),
                report.getExecutedAt()
        );
    }
}
