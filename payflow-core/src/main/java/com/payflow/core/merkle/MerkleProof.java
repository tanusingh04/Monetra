package com.payflow.core.merkle;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public class MerkleProof implements Serializable {

    private UUID entryId;
    private String leafHash;
    private int leafIndex;
    private int leafCount;
    private Long batchNumber;
    private String rootHash;
    private String chainHash;
    private String previousRootHash;
    private List<MerkleAuditPathStep> auditPath;
    private String canonicalPayload;
    private boolean verified;

    public MerkleProof() {}

    public MerkleProof(UUID entryId, String leafHash, int leafIndex, int leafCount,
                       Long batchNumber, String rootHash, String chainHash,
                       String previousRootHash, List<MerkleAuditPathStep> auditPath,
                       String canonicalPayload, boolean verified) {
        this.entryId = entryId;
        this.leafHash = leafHash;
        this.leafIndex = leafIndex;
        this.leafCount = leafCount;
        this.batchNumber = batchNumber;
        this.rootHash = rootHash;
        this.chainHash = chainHash;
        this.previousRootHash = previousRootHash;
        this.auditPath = auditPath;
        this.canonicalPayload = canonicalPayload;
        this.verified = verified;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public String getLeafHash() {
        return leafHash;
    }

    public int getLeafIndex() {
        return leafIndex;
    }

    public int getLeafCount() {
        return leafCount;
    }

    public Long getBatchNumber() {
        return batchNumber;
    }

    public String getRootHash() {
        return rootHash;
    }

    public String getChainHash() {
        return chainHash;
    }

    public String getPreviousRootHash() {
        return previousRootHash;
    }

    public List<MerkleAuditPathStep> getAuditPath() {
        return auditPath;
    }

    public String getCanonicalPayload() {
        return canonicalPayload;
    }

    public boolean isVerified() {
        return verified;
    }
}
