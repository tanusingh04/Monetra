package com.payflow.api.dto.merkle;

import com.payflow.core.merkle.MerkleAuditPathStep;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MerkleProofResponse(
        String status,
        UUID entryId,
        String leafHash,
        Integer leafIndex,
        Integer leafCount,
        Long batchNumber,
        String rootHash,
        String chainHash,
        String previousRootHash,
        List<MerkleAuditPathStep> auditPath,
        String canonicalPayload,
        Boolean verified,
        String message
) {
    public static MerkleProofResponse pending(UUID entryId) {
        return new MerkleProofResponse(
                "PENDING_ANCHORING",
                entryId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "Entry has been committed to ledger but not yet anchored in a Merkle batch"
        );
    }
}
