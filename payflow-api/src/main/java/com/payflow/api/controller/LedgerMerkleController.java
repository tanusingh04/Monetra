package com.payflow.api.controller;

import com.payflow.api.dto.merkle.BatchVerificationReport;
import com.payflow.api.dto.merkle.LedgerMerkleBatchResponse;
import com.payflow.api.dto.merkle.MerkleProofResponse;
import com.payflow.api.merkle.LedgerMerkleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@RestController
@Tag(name = "Ledger Merkle Proofs", description = "Endpoints for cryptographically sealed Merkle root inspection and inclusion proofs")
public class LedgerMerkleController {

    private final LedgerMerkleService merkleService;

    public LedgerMerkleController(LedgerMerkleService merkleService) {
        this.merkleService = merkleService;
    }

    @GetMapping("/api/ledger/entries/{entryId}/proof")
    @Operation(summary = "Retrieve cryptographic Merkle inclusion proof for a ledger entry")
    public ResponseEntity<MerkleProofResponse> getMerkleProof(
            @PathVariable UUID entryId,
            @AuthenticationPrincipal UserDetails userDetails) {

        String username = userDetails != null ? userDetails.getUsername() : "";
        boolean isAdmin = userDetails != null && userDetails.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        Optional<MerkleProofResponse> proofOpt = merkleService.getMerkleProof(entryId, username, isAdmin);

        if (proofOpt.isPresent()) {
            return ResponseEntity.ok(proofOpt.get());
        } else {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(MerkleProofResponse.pending(entryId));
        }
    }

    @GetMapping("/api/public/ledger/roots")
    @Operation(summary = "Retrieve paginated list of cryptographically committed Merkle batch roots")
    public ResponseEntity<Page<LedgerMerkleBatchResponse>> getRoots(
            @PageableDefault(size = 20, sort = "batchNumber") Pageable pageable) {
        return ResponseEntity.ok(merkleService.getRoots(pageable));
    }

    @GetMapping("/api/public/ledger/roots/latest")
    @Operation(summary = "Retrieve the latest cryptographically committed Merkle batch root")
    public ResponseEntity<LedgerMerkleBatchResponse> getLatestRoot() {
        return merkleService.getLatestRoot()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/api/admin/ledger/batches/{batchNumber}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Cryptographically re-verify an entire Merkle batch and chain linkage")
    public ResponseEntity<BatchVerificationReport> verifyBatch(@PathVariable Long batchNumber) {
        return ResponseEntity.ok(merkleService.verifyBatch(batchNumber));
    }
}
