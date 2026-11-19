package com.payflow.api.dto.merkle;

import java.time.Instant;

public record BatchVerificationReport(
        Long batchNumber,
        int leafCount,
        String rootHash,
        String recomputedRootHash,
        boolean rootVerified,
        String chainHash,
        String recomputedChainHash,
        boolean chainVerified,
        boolean valid,
        Instant verifiedAt
) {}
