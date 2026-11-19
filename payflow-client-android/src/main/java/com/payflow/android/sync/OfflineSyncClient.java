package com.payflow.android.sync;

import com.payflow.android.model.PaymentReceipt;
import com.payflow.android.storage.EncryptedOfflineStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client-side synchronization manager responsible for batching offline receipts,
 * managing network retry policies, and pruning local receipt journals upon confirmed settlement.
 */
public class OfflineSyncClient {

    private final EncryptedOfflineStorage storage;

    public OfflineSyncClient(EncryptedOfflineStorage storage) {
        this.storage = storage;
    }

    public List<PaymentReceipt> preparePendingBatch() {
        return storage.getPendingReceiptsForSync();
    }

    public void onSyncSuccess(List<UUID> settledOrDuplicateTxIds) {
        storage.markReceiptsSynced(settledOrDuplicateTxIds);
    }
}
