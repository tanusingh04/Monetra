package com.payflow.api.controller;

import com.payflow.api.dto.common.ApiResponse;
import com.payflow.api.dto.offline.OfflineSyncRequest;
import com.payflow.api.dto.offline.OfflineSyncResponse;
import com.payflow.api.security.UserPrincipal;
import com.payflow.api.service.offline.OfflineSettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/offline/sync")
@Tag(name = "Offline Settlement", description = "Endpoints for synchronizing and settling offline signed transaction receipts")
public class OfflineSettlementController {

    private final OfflineSettlementService settlementService;

    public OfflineSettlementController(OfflineSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping("/settle")
    @Operation(summary = "Synchronize batch of offline signed receipts and execute atomic double-entry settlement")
    public ResponseEntity<ApiResponse<OfflineSyncResponse>> settleOfflineBatch(
            @Valid @RequestBody OfflineSyncRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        OfflineSyncResponse response = settlementService.settleOfflineBatch(request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Offline receipts synchronization completed", response));
    }
}
