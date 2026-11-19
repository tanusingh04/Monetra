package com.payflow.api.controller;

import com.payflow.api.dto.common.ApiResponse;
import com.payflow.api.dto.offline.FraudProofResponse;
import com.payflow.api.dto.offline.FraudProofVerificationResponse;
import com.payflow.api.dto.offline.ReviewFraudProofRequest;
import com.payflow.api.security.UserPrincipal;
import com.payflow.api.service.offline.FraudProofService;
import com.payflow.core.domain.offline.FraudProof;
import com.payflow.core.domain.offline.FraudProofStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/offline/fraud-proofs")
@Tag(name = "Offline Fraud Proofs", description = "Administration endpoints for inspecting, reviewing, and cryptographically verifying offline equivocation fraud proofs")
@SecurityRequirement(name = "bearerAuth")
public class FraudProofController {

    private final FraudProofService fraudProofService;

    public FraudProofController(FraudProofService fraudProofService) {
        this.fraudProofService = fraudProofService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fetch paginated offline fraud proofs with optional status filter (Admin only)")
    public ResponseEntity<ApiResponse<Page<FraudProofResponse>>> getFraudProofs(
            @RequestParam(required = false) FraudProofStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<FraudProof> proofs = fraudProofService.getFraudProofs(status, pageable);
        return ResponseEntity.ok(ApiResponse.success(proofs.map(FraudProofResponse::from)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fetch fraud proof details by ID (Admin only)")
    public ResponseEntity<ApiResponse<FraudProofResponse>> getFraudProof(@PathVariable UUID id) {
        FraudProof proof = fraudProofService.getFraudProof(id);
        return ResponseEntity.ok(ApiResponse.success(FraudProofResponse.from(proof)));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Review and resolve fraud proof status (CONFIRMED or DISMISSED) (Admin only)")
    public ResponseEntity<ApiResponse<FraudProofResponse>> reviewFraudProof(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewFraudProofRequest request,
            java.security.Principal principal) {
        String reviewer = principal != null ? principal.getName() : "admin";
        FraudProof updated = fraudProofService.reviewFraudProof(id, request.decision(), reviewer, request.note());
        return ResponseEntity.ok(ApiResponse.success("Fraud proof review recorded", FraudProofResponse.from(updated)));
    }

    @GetMapping("/{id}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Cryptographically re-verify both receipt signatures and payload disparity for third-party auditing (Admin only)")
    public ResponseEntity<ApiResponse<FraudProofVerificationResponse>> verifyFraudProof(@PathVariable UUID id) {
        FraudProofService.FraudProofVerificationResult result = fraudProofService.verifyFraudProof(id);
        return ResponseEntity.ok(ApiResponse.success("Cryptographic verification completed", FraudProofVerificationResponse.from(result)));
    }
}
