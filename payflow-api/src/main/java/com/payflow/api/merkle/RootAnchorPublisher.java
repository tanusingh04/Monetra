package com.payflow.api.merkle;

public interface RootAnchorPublisher {
    void publishRootAnchor(Long batchNumber, int leafCount, String rootHash, String chainHash);
}
