package com.payflow.api.controller;

import com.payflow.api.dto.common.ApiResponse;
import com.payflow.api.dto.reputation.ReputationOverrideRequest;
import com.payflow.api.dto.reputation.ReputationResponse;
import com.payflow.api.security.UserPrincipal;
import com.payflow.api.service.offline.ReputationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Tag(name = "User Reputation", description = "Endpoints for checking and managing offline allowance reputation scores and tiers")
@SecurityRequirement(name = "bearerAuth")
public class ReputationController {

    private final ReputationService reputationService;

    public ReputationController(ReputationService reputationService) {
        this.reputationService = reputationService;
    }

    @GetMapping("/api/offline/reputation/me")
    @Operation(summary = "Fetch current user's reputation score, tier limits, progress to next tier, and recent audit events")
    public ResponseEntity<ApiResponse<ReputationResponse>> getMyReputation(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        ReputationResponse response = reputationService.getReputationResponse(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("User reputation retrieved successfully", response));
    }

    @GetMapping("/api/admin/offline/reputation/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fetch reputation score and history for a specific user (Admin only)")
    public ResponseEntity<ApiResponse<ReputationResponse>> getUserReputation(
            @PathVariable UUID userId) {
        ReputationResponse response = reputationService.getReputationResponse(userId);
        return ResponseEntity.ok(ApiResponse.success("User reputation retrieved successfully", response));
    }

    @PostMapping("/api/admin/offline/reputation/{userId}/override")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Administratively override a user's reputation tier, score, or restriction status (Admin only)")
    public ResponseEntity<ApiResponse<ReputationResponse>> overrideUserReputation(
            @PathVariable UUID userId,
            @Valid @RequestBody ReputationOverrideRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        ReputationResponse response = reputationService.adminOverride(userId, request, currentUser.getUsername());
        return ResponseEntity.ok(ApiResponse.success("User reputation successfully overridden", response));
    }
}
