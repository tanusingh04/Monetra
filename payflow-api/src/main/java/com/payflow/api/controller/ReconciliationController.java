package com.payflow.api.controller;

import com.payflow.api.dto.common.ApiResponse;
import com.payflow.api.dto.reconciliation.ReconciliationReportResponse;
import com.payflow.api.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/reconciliation")
@Tag(name = "Reconciliation Engine", description = "Financial audits, ledger parity validation, and discrepancy reports")
@SecurityRequirement(name = "bearerAuth")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Execute system-wide financial reconciliation audit (Admin only)")
    public ResponseEntity<ApiResponse<ReconciliationReportResponse>> runReconciliation() {
        ReconciliationReportResponse report = reconciliationService.runReconciliation();
        return ResponseEntity.ok(ApiResponse.success("Reconciliation audit completed", report));
    }

    @GetMapping("/reports")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fetch historical reconciliation audit reports (Admin only)")
    public ResponseEntity<ApiResponse<Page<ReconciliationReportResponse>>> getReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<ReconciliationReportResponse> reports = reconciliationService.getReports(pageable);
        return ResponseEntity.ok(ApiResponse.success(reports));
    }
}
