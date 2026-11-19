package com.payflow.android.merkle;

import java.io.Serializable;
import java.util.Objects;

public class MerkleAuditPathStep implements Serializable {

    private String siblingHash;
    private boolean isRightSide;

    public MerkleAuditPathStep() {}

    public MerkleAuditPathStep(String siblingHash, boolean isRightSide) {
        this.siblingHash = siblingHash;
        this.isRightSide = isRightSide;
    }

    public String getSiblingHash() {
        return siblingHash;
    }

    public boolean isRightSide() {
        return isRightSide;
    }

    public void setSiblingHash(String siblingHash) {
        this.siblingHash = siblingHash;
    }

    public void setRightSide(boolean rightSide) {
        isRightSide = rightSide;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MerkleAuditPathStep that = (MerkleAuditPathStep) o;
        return isRightSide == that.isRightSide && Objects.equals(siblingHash, that.siblingHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(siblingHash, isRightSide);
    }
}
