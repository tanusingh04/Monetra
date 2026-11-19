package com.payflow.api.service.offline;

import com.payflow.api.dto.offline.OfflineReceiptDto;
import com.payflow.api.dto.offline.OfflineSyncItemResult;
import com.payflow.api.dto.offline.OfflineSyncRequest;
import com.payflow.api.dto.offline.OfflineSyncResponse;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.payment.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service managing offline wallet synchronizations and batch settlements.
 * Each receipt is settled in an isolated REQUIRES_NEW transaction, ensuring
 * that failures or fraud proofs on one receipt never roll back others in the batch.
 */
@Service
public class OfflineSettlementService {

    private static final Logger log = LoggerFactory.getLogger(OfflineSettlementService.class);

    private final OfflineReceiptSettlementExecutor singleReceiptExecutor;
    private final OfflineAtomicSettlementExecutor atomicSettlementExecutor;

    public OfflineSettlementService(OfflineReceiptSettlementExecutor singleReceiptExecutor,
                                    OfflineAtomicSettlementExecutor atomicSettlementExecutor) {
        this.singleReceiptExecutor = singleReceiptExecutor;
        this.atomicSettlementExecutor = atomicSettlementExecutor;
    }

    /**
     * Settles a batch of offline receipts submitted by a user.
     * Each receipt is settled in an isolated transaction so that individual rejections
     * or exceptions do not roll back successful settlements or detected fraud proofs.
     */
    public OfflineSyncResponse settleOfflineBatch(OfflineSyncRequest request, UUID callingUserId) {
        log.info("Processing offline synchronization batch of {} receipts submitted by user {}",
                request.receipts().size(), callingUserId);

        List<OfflineSyncItemResult> results = new ArrayList<>();
        int settledCount = 0;
        int rejectedCount = 0;

        for (OfflineReceiptDto receipt : request.receipts()) {
            try {
                OfflineSyncItemResult itemResult = singleReceiptExecutor.settleSingleReceiptIsolated(receipt);
                results.add(itemResult);
                if ("SETTLED".equals(itemResult.status()) || "ALREADY_SETTLED".equals(itemResult.status())) {
                    settledCount++;
                } else {
                    rejectedCount++;
                }
            } catch (Exception ex) {
                log.error("Unhandled exception settling offline receipt {}: {}", receipt.txId(), ex.getMessage(), ex);
                results.add(new OfflineSyncItemResult(receipt.txId(), "ERROR", null, "Internal settlement error: " + ex.getMessage()));
                rejectedCount++;
            }
        }

        return new OfflineSyncResponse(request.receipts().size(), settledCount, rejectedCount, results);
    }

    /**
     * Settles a single offline receipt in an isolated transaction.
     */
    @Transactional
    public OfflineSyncItemResult settleSingleReceipt(OfflineReceiptDto receipt) {
        return singleReceiptExecutor.settleSingleReceiptIsolated(receipt);
    }

    /**
     * Unified reusable method for atomic financial settlement.
     */
    @Transactional
    public Payment performAtomicSettlement(OfflineReceiptDto receipt, OfflineAllowance allowance, SettlementMode mode) {
        return atomicSettlementExecutor.performAtomicSettlement(receipt, allowance, mode, null);
    }
}
