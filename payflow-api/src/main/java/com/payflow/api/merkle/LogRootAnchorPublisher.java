package com.payflow.api.merkle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(value = RootAnchorPublisher.class, ignored = LogRootAnchorPublisher.class)
public class LogRootAnchorPublisher implements RootAnchorPublisher {

    private static final Logger log = LoggerFactory.getLogger(LogRootAnchorPublisher.class);

    @Override
    public void publishRootAnchor(Long batchNumber, int leafCount, String rootHash, String chainHash) {
        log.info("[MERKLE_ROOT_ANCHOR] Batch #{}: leaves={}, rootHash={}, chainHash={}",
                batchNumber, leafCount, rootHash, chainHash);
    }
}
