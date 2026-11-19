package com.payflow.core.event;

import java.io.Serializable;
import java.time.Instant;

public record LedgerRootPublishedEvent(
        Long batchNumber,
        int leafCount,
        String rootHash,
        String chainHash,
        String previousRootHash,
        Instant publishedAt
) implements Serializable {}
