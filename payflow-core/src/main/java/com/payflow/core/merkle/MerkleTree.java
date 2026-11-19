package com.payflow.core.merkle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RFC 6962 Compliant Merkle Tree Implementation.
 * Uses SHA-256 with domain separation prefixes:
 * - 0x00 for leaves: MTH({d}) = SHA-256(0x00 || d)
 * - 0x01 for internal nodes: MTH(D_n) = SHA-256(0x01 || left || right)
 * Handles non-power-of-two leaf counts by splitting at the largest power of 2 less than n:
 * k = 2^(floor(log2(n-1))) = Integer.highestOneBit(n - 1).
 */
public class MerkleTree {

    private static class Node {
        final String hash;
        final int size;
        final Node left;
        final Node right;

        Node(String hash, int size, Node left, Node right) {
            this.hash = hash;
            this.size = size;
            this.left = left;
            this.right = right;
        }

        boolean isLeaf() {
            return left == null && right == null;
        }
    }

    private final Node root;
    private final int leafCount;

    public MerkleTree(List<String> leafHashes) {
        if (leafHashes == null || leafHashes.isEmpty()) {
            throw new IllegalArgumentException("Cannot construct Merkle tree with null or empty leaves");
        }
        this.leafCount = leafHashes.size();
        this.root = buildSubtree(leafHashes, 0, leafHashes.size());
    }

    private Node buildSubtree(List<String> leaves, int from, int to) {
        int count = to - from;
        if (count == 1) {
            return new Node(leaves.get(from), 1, null, null);
        }
        int k = Integer.highestOneBit(count - 1);
        Node leftChild = buildSubtree(leaves, from, from + k);
        Node rightChild = buildSubtree(leaves, from + k, to);
        String parentHash = CanonicalLedgerEntry.computeNodeHash(leftChild.hash, rightChild.hash);
        return new Node(parentHash, count, leftChild, rightChild);
    }

    public String getRootHash() {
        return root.hash;
    }

    public int getLeafCount() {
        return leafCount;
    }

    public List<MerkleAuditPathStep> getAuditPath(int leafIndex) {
        if (leafIndex < 0 || leafIndex >= leafCount) {
            throw new IndexOutOfBoundsException("Leaf index " + leafIndex + " out of bounds for tree size " + leafCount);
        }
        List<MerkleAuditPathStep> path = new ArrayList<>();
        collectAuditPath(root, leafIndex, path);
        return Collections.unmodifiableList(path);
    }

    private void collectAuditPath(Node current, int index, List<MerkleAuditPathStep> path) {
        if (current.isLeaf()) {
            return;
        }
        int k = current.left.size;
        if (index < k) {
            collectAuditPath(current.left, index, path);
            path.add(new MerkleAuditPathStep(current.right.hash, true));
        } else {
            collectAuditPath(current.right, index - k, path);
            path.add(new MerkleAuditPathStep(current.left.hash, false));
        }
    }
}
