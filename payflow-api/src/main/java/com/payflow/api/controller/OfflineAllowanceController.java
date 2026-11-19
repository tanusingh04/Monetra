package com.payflow.api.controller;

import com.payflow.api.dto.common.ApiResponse;
import com.payflow.api.dto.offline.AllocateAllowanceRequest;
import com.payflow.api.dto.offline.AllowanceCertificateResponse;
import com.payflow.api.security.UserPrincipal;
import com.payflow.api.service.offline.OfflineAllowanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/offline/allowance")
@Tag(name = "Offline Allowance", description = "Endpoints for reserving offline spending allowances and certificate management")
public class OfflineAllowanceController {

    private final OfflineAllowanceService allowanceService;

    public OfflineAllowanceController(OfflineAllowanceService allowanceService) {
        this.allowanceService = allowanceService;
    }

    @PostMapping("/allocate")
    @Operation(summary = "Atomically reserve funds from online wallet and issue signed offline allowance certificate")
    public ResponseEntity<ApiResponse<AllowanceCertificateResponse>> allocateAllowance(
            @Valid @RequestBody AllocateAllowanceRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        AllowanceCertificateResponse response = allowanceService.allocateAllowance(request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Offline allowance successfully allocated and signed", response));
    }

    @GetMapping("/active")
    @Operation(summary = "Fetch active offline allowance certificate for a wallet")
    public ResponseEntity<ApiResponse<AllowanceCertificateResponse>> getActiveAllowance(
            @RequestParam UUID walletId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return allowanceService.getActiveAllowance(walletId, currentUser.getId())
                .map(a -> ResponseEntity.ok(ApiResponse.success("Active allowance retrieved", a)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.success("No active allowance found", null)));
    }

    @PostMapping("/{allowanceId}/release")
    @Operation(summary = "Release unspent reserved offline funds back to available wallet balance")
    public ResponseEntity<ApiResponse<AllowanceCertificateResponse>> releaseAllowance(
            @PathVariable UUID allowanceId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        AllowanceCertificateResponse response = allowanceService.releaseAllowance(allowanceId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Unspent reserved funds released back to wallet balance", response));
    }
}
