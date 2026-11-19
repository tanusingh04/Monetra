package com.payflow.core.merkle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MerkleTreeTest {

    @ParameterizedTest(name = "RFC 6962 tree with {0} leaves verifies inclusion for all leaves")
    @ValueSource(ints = {1, 2, 3, 4, 5, 7, 8, 15, 16, 23})
    void testTreeInclusionProofAllLeaves(int leafCount) {
        List<String> leaves = new ArrayList<>();
        for (int i = 0; i < leafCount; i++) {
            leaves.add(CanonicalLedgerEntry.computeLeafHash("leaf-data-" + i));
        }

        MerkleTree tree = new MerkleTree(leaves);
        assertThat(tree.getLeafCount()).isEqualTo(leafCount);
        assertThat(tree.getRootHash()).hasSize(64);

        for (int i = 0; i < leafCount; i++) {
            List<MerkleAuditPathStep> auditPath = tree.getAuditPath(i);
            boolean verified = MerkleVerifier.verifyInclusion(
                    leaves.get(i), i, leafCount, auditPath, tree.getRootHash()
            );
            assertThat(verified).isTrue();
        }
    }

    @Test
    @DisplayName("Single-leaf tree: root hash equals leaf hash, audit path is empty")
    void testSingleLeafTree() {
        String leaf = CanonicalLedgerEntry.computeLeafHash("single-leaf-tx");
        MerkleTree tree = new MerkleTree(List.of(leaf));

        assertThat(tree.getRootHash()).isEqualTo(leaf);
        List<MerkleAuditPathStep> path = tree.getAuditPath(0);
        assertThat(path).isEmpty();

        boolean verified = MerkleVerifier.verifyInclusion(leaf, 0, 1, path, tree.getRootHash());
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("Tampered leaf hash fails verification")
    void testTamperedLeafFails() {
        List<String> leaves = List.of(
                CanonicalLedgerEntry.computeLeafHash("tx-1"),
                CanonicalLedgerEntry.computeLeafHash("tx-2"),
                CanonicalLedgerEntry.computeLeafHash("tx-3")
        );
        MerkleTree tree = new MerkleTree(leaves);
        List<MerkleAuditPathStep> path = tree.getAuditPath(0);

        // Bit flip in leaf hash
        String tamperedLeaf = leaves.get(0).substring(0, 63) + (leaves.get(0).endsWith("a") ? "b" : "a");
        boolean verified = MerkleVerifier.verifyInclusion(tamperedLeaf, 0, 3, path, tree.getRootHash());
        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("Tampered sibling hash in audit path fails verification")
    void testTamperedSiblingHashFails() {
        List<String> leaves = List.of(
                CanonicalLedgerEntry.computeLeafHash("tx-1"),
                CanonicalLedgerEntry.computeLeafHash("tx-2"),
                CanonicalLedgerEntry.computeLeafHash("tx-3"),
                CanonicalLedgerEntry.computeLeafHash("tx-4")
        );
        MerkleTree tree = new MerkleTree(leaves);
        List<MerkleAuditPathStep> path = tree.getAuditPath(0);

        // Mutate first sibling hash
        List<MerkleAuditPathStep> tamperedPath = new ArrayList<>();
        MerkleAuditPathStep originalStep = path.get(0);
        String mutatedSibling = originalStep.getSiblingHash().substring(0, 63) + "f";
        tamperedPath.add(new MerkleAuditPathStep(mutatedSibling, originalStep.isRightSide()));
        for (int i = 1; i < path.size(); i++) {
            tamperedPath.add(path.get(i));
        }

        boolean verified = MerkleVerifier.verifyInclusion(leaves.get(0), 0, 4, tamperedPath, tree.getRootHash());
        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("Flipped sibling orientation (isRightSide) fails verification")
    void testFlippedSiblingOrientationFails() {
        List<String> leaves = List.of(
                CanonicalLedgerEntry.computeLeafHash("tx-1"),
                CanonicalLedgerEntry.computeLeafHash("tx-2")
        );
        MerkleTree tree = new MerkleTree(leaves);
        List<MerkleAuditPathStep> path = tree.getAuditPath(0);
        assertThat(path.get(0).isRightSide()).isTrue();

        // Flip side
        List<MerkleAuditPathStep> flippedPath = List.of(
                new MerkleAuditPathStep(path.get(0).getSiblingHash(), false)
        );

        boolean verified = MerkleVerifier.verifyInclusion(leaves.get(0), 0, 2, flippedPath, tree.getRootHash());
        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("Chain hash links previous root and current root deterministically")
    void testChainHashComputation() {
        String prevRoot = CanonicalLedgerEntry.GENESIS_PREVIOUS_ROOT;
        String rootHash = CanonicalLedgerEntry.computeLeafHash("batch-1-root");
        String chainHash1 = CanonicalLedgerEntry.computeChainHash(prevRoot, rootHash, 1L, 10);
        String chainHash2 = CanonicalLedgerEntry.computeChainHash(prevRoot, rootHash, 1L, 10);

        assertThat(chainHash1).isEqualTo(chainHash2);
        assertThat(chainHash1).hasSize(64);
    }

    @Test
    @DisplayName("Empty or null leaves throw IllegalArgumentException")
    void testEmptyLeavesThrow() {
        assertThatThrownBy(() -> new MerkleTree(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MerkleTree(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Out-of-bounds leaf index throws IndexOutOfBoundsException")
    void testOutOfBoundsIndexThrows() {
        MerkleTree tree = new MerkleTree(List.of(CanonicalLedgerEntry.computeLeafHash("tx-1")));
        assertThatThrownBy(() -> tree.getAuditPath(1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> tree.getAuditPath(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }
}
