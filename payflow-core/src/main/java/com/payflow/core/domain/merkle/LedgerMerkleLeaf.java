package com.payflow.core.domain.merkle;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_merkle_leaves",
       uniqueConstraints = @UniqueConstraint(name = "uq_merkle_batch_leaf_index", columnNames = {"batch_number", "leaf_index"}))
public class LedgerMerkleLeaf {

    @Id
    @Column(name = "entry_id", nullable = false, updatable = false)
    private UUID entryId;

    @Column(name = "batch_number", nullable = false, updatable = false)
    private Long batchNumber;

    @Column(name = "leaf_index", nullable = false, updatable = false)
    private int leafIndex;

    @Column(name = "leaf_hash", nullable = false, updatable = false, length = 64)
    private String leafHash;

    public LedgerMerkleLeaf() {}

    public LedgerMerkleLeaf(UUID entryId, Long batchNumber, int leafIndex, String leafHash) {
        this.entryId = entryId;
        this.batchNumber = batchNumber;
        this.leafIndex = leafIndex;
        this.leafHash = leafHash;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public Long getBatchNumber() {
        return batchNumber;
    }

    public int getLeafIndex() {
        return leafIndex;
    }

    public String getLeafHash() {
        return leafHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerMerkleLeaf that = (LedgerMerkleLeaf) o;
        return Objects.equals(entryId, that.entryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entryId);
    }
}
