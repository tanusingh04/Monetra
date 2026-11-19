package com.payflow.core.domain.merkle;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "ledger_merkle_batches")
public class LedgerMerkleBatch {

    @Id
    @Column(name = "batch_number", nullable = false, updatable = false)
    private Long batchNumber;

    @Column(name = "leaf_count", nullable = false, updatable = false)
    private int leafCount;

    @Column(name = "root_hash", nullable = false, updatable = false, length = 64)
    private String rootHash;

    @Column(name = "previous_root_hash", nullable = false, updatable = false, length = 64)
    private String previousRootHash;

    @Column(name = "chain_hash", nullable = false, updatable = false, length = 64)
    private String chainHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "first_entry_created_at", nullable = false, updatable = false)
    private Instant firstEntryCreatedAt;

    @Column(name = "last_entry_created_at", nullable = false, updatable = false)
    private Instant lastEntryCreatedAt;

    public LedgerMerkleBatch() {}

    public LedgerMerkleBatch(Long batchNumber, int leafCount, String rootHash,
                              String previousRootHash, String chainHash,
                              Instant firstEntryCreatedAt, Instant lastEntryCreatedAt) {
        this.batchNumber = batchNumber;
        this.leafCount = leafCount;
        this.rootHash = rootHash;
        this.previousRootHash = previousRootHash;
        this.chainHash = chainHash;
        this.createdAt = Instant.now();
        this.firstEntryCreatedAt = firstEntryCreatedAt;
        this.lastEntryCreatedAt = lastEntryCreatedAt;
    }

    public Long getBatchNumber() {
        return batchNumber;
    }

    public int getLeafCount() {
        return leafCount;
    }

    public String getRootHash() {
        return rootHash;
    }

    public String getPreviousRootHash() {
        return previousRootHash;
    }

    public String getChainHash() {
        return chainHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFirstEntryCreatedAt() {
        return firstEntryCreatedAt;
    }

    public Instant getLastEntryCreatedAt() {
        return lastEntryCreatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerMerkleBatch that = (LedgerMerkleBatch) o;
        return Objects.equals(batchNumber, that.batchNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(batchNumber);
    }
}
