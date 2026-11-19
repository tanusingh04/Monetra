package com.payflow.android.merkle;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Pure Java, framework-free RFC 6962 Merkle inclusion proof verifier for Android.
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
                currentHash = computeNodeHash(currentHash, step.getSiblingHash());
            } else {
                currentHash = computeNodeHash(step.getSiblingHash(), currentHash);
            }
        }

        return currentHash.equalsIgnoreCase(expectedRootHash);
    }

    private static String computeNodeHash(String leftHex, String rightHex) {
        byte[] left = HexFormat.of().parseHex(leftHex);
        byte[] right = HexFormat.of().parseHex(rightHex);
        byte[] input = new byte[1 + 32 + 32];
        input[0] = 0x01; // RFC 6962 internal node prefix
        System.arraycopy(left, 0, input, 1, 32);
        System.arraycopy(right, 0, input, 33, 32);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
