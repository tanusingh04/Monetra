package com.payflow.core.merkle;

import java.util.List;

/**
 * Pure Java, framework-free RFC 6962 Merkle inclusion proof verifier.
 */
public final class MerkleVerifier {

    private MerkleVerifier() {}

    /**
     * Verifies an RFC 6962 Merkle inclusion proof.
     *
     * @param leafHash         64-char hex hash of the leaf
     * @param leafIndex        0-based index of the leaf in the batch
     * @param totalLeaves      total number of leaves in the batch
     * @param auditPath        ordered list of sibling steps from leaf to root
     * @param expectedRootHash 64-char hex expected root hash
     * @return true if proof is cryptographically valid, false otherwise
     */
    public static boolean verifyInclusion(String leafHash, int leafIndex, int totalLeaves,
                                          List<MerkleAuditPathStep> auditPath, String expectedRootHash) {
        if (leafHash == null || expectedRootHash == null || auditPath == null) {
            return false;
        }
        if (leafIndex < 0 || totalLeaves <= 0 || leafIndex >= totalLeaves) {
            return false;
        }

        String currentHash = leafHash;
        for (MerkleAuditPathStep step : auditPath) {
            if (step == null || step.getSiblingHash() == null) {
                return false;
            }
            if (step.isRightSide()) {
                currentHash = CanonicalLedgerEntry.computeNodeHash(currentHash, step.getSiblingHash());
            } else {
                currentHash = CanonicalLedgerEntry.computeNodeHash(step.getSiblingHash(), currentHash);
            }
        }

        return currentHash.equalsIgnoreCase(expectedRootHash);
    }
}
