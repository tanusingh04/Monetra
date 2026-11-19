package com.payflow.api.dto.merkle;

import com.payflow.core.domain.merkle.LedgerMerkleBatch;

import java.time.Instant;

public record LedgerMerkleBatchResponse(
        Long batchNumber,
        int leafCount,
        String rootHash,
        String previousRootHash,
        String chainHash,
        Instant createdAt,
        Instant firstEntryCreatedAt,
        Instant lastEntryCreatedAt
) {
    public static LedgerMerkleBatchResponse fromEntity(LedgerMerkleBatch batch) {
        return new LedgerMerkleBatchResponse(
                batch.getBatchNumber(),
                batch.getLeafCount(),
                batch.getRootHash(),
                batch.getPreviousRootHash(),
                batch.getChainHash(),
                batch.getCreatedAt(),
                batch.getFirstEntryCreatedAt(),
                batch.getLastEntryCreatedAt()
        );
    }
}
