package com.payflow.android.storage;

import com.payflow.android.model.AllowanceCertificate;
import com.payflow.android.model.PaymentReceipt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe encrypted local storage simulating Android EncryptedSharedPreferences
 * and Room SQLCipher persistence for offline balances, monotonic counters, and receipts.
 */
public class EncryptedOfflineStorage {

    private volatile AllowanceCertificate activeAllowance;
    private volatile BigDecimal spentAmount = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private final AtomicLong monotonicCounter = new AtomicLong(0);

    // List of receipts collected by this device (when acting as receiver) awaiting synchronization
    private final List<PaymentReceipt> pendingReceipts = new CopyOnWriteArrayList<>();

    public synchronized void saveAllowance(AllowanceCertificate certificate) {
        this.activeAllowance = certificate;
        this.spentAmount = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        this.monotonicCounter.set(0);
    }

    public synchronized Optional<AllowanceCertificate> getActiveAllowance() {
        if (activeAllowance != null && activeAllowance.isExpired()) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeAllowance);
    }

    public synchronized BigDecimal getRemainingBalance() {
        if (activeAllowance == null || activeAllowance.isExpired()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return activeAllowance.allocatedAmount().subtract(spentAmount).setScale(4, RoundingMode.HALF_UP);
    }

    public synchronized long getNextCounter() {
        return monotonicCounter.incrementAndGet();
    }

    public synchronized void recordSpend(BigDecimal amount) {
        BigDecimal scaled = amount.setScale(4, RoundingMode.HALF_UP);
        this.spentAmount = this.spentAmount.add(scaled);
    }

    public void saveReceivedReceipt(PaymentReceipt receipt) {
        pendingReceipts.add(receipt);
    }

    public List<PaymentReceipt> getPendingReceiptsForSync() {
        return Collections.unmodifiableList(new ArrayList<>(pendingReceipts));
    }

    public void markReceiptsSynced(List<UUID> syncedTxIds) {
        pendingReceipts.removeIf(r -> syncedTxIds.contains(r.txId()));
    }

    public synchronized void clear() {
        this.activeAllowance = null;
        this.spentAmount = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        this.monotonicCounter.set(0);
        this.pendingReceipts.clear();
    }
}
