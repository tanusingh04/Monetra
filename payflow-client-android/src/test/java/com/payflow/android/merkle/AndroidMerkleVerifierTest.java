package com.payflow.android.merkle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AndroidMerkleVerifierTest {

    private String leafHash(String data) throws Exception {
        byte[] bytes = data.getBytes();
        byte[] input = new byte[1 + bytes.length];
        input[0] = 0x00;
        System.arraycopy(bytes, 0, input, 1, bytes.length);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    private String nodeHash(String leftHex, String rightHex) throws Exception {
        byte[] left = HexFormat.of().parseHex(leftHex);
        byte[] right = HexFormat.of().parseHex(rightHex);
        byte[] input = new byte[1 + 32 + 32];
        input[0] = 0x01;
        System.arraycopy(left, 0, input, 1, 32);
        System.arraycopy(right, 0, input, 33, 32);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    @Test
    @DisplayName("Android MerkleVerifier validates 2-leaf tree inclusion proof")
    void testTwoLeafVerification() throws Exception {
        String leaf0 = leafHash("tx0");
        String leaf1 = leafHash("tx1");
        String root = nodeHash(leaf0, leaf1);

        List<MerkleAuditPathStep> pathForLeaf0 = List.of(new MerkleAuditPathStep(leaf1, true));
        boolean valid0 = MerkleVerifier.verifyInclusion(leaf0, 0, 2, pathForLeaf0, root);
        assertTrue(valid0);

        List<MerkleAuditPathStep> pathForLeaf1 = List.of(new MerkleAuditPathStep(leaf0, false));
        boolean valid1 = MerkleVerifier.verifyInclusion(leaf1, 1, 2, pathForLeaf1, root);
        assertTrue(valid1);
    }

    @Test
    @DisplayName("Android MerkleVerifier detects invalid root hash")
    void testInvalidRootHash() throws Exception {
        String leaf0 = leafHash("tx0");
        String leaf1 = leafHash("tx1");
        String fakeRoot = "0000000000000000000000000000000000000000000000000000000000000000";

        List<MerkleAuditPathStep> path = List.of(new MerkleAuditPathStep(leaf1, true));
        boolean valid = MerkleVerifier.verifyInclusion(leaf0, 0, 2, path, fakeRoot);
        assertFalse(valid);
    }
}
