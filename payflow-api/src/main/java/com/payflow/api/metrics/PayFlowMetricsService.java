package com.payflow.api.metrics;

import com.payflow.core.domain.outbox.OutboxStatus;
import com.payflow.core.repository.OutboxEventRepository;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Centralized service managing custom Micrometer metrics for PayFlow.
 * Exposes payment throughput, failure modes, latency percentiles,
 * cache efficiency, outbox backpressure, and database pool utilization.
 */
@Service
public class PayFlowMetricsService {

    private final MeterRegistry registry;
    private final Counter totalPaymentRequestsCounter;
    private final Counter successfulPaymentsCounter;
    private final Counter failedPaymentsCounter;
    private final Timer paymentProcessingTimer;
    private final Counter idempotencyConflictsCounter;
    private final Counter insufficientBalanceCounter;
    private final Counter outboxPublishFailuresCounter;
    private final Counter redisCacheHitsCounter;
    private final Counter redisCacheMissesCounter;
    private final Counter reconciliationFailuresCounter;
    private final Counter fraudProofsDetectedCounter;
    private final Counter fraudProofsRejectedInvalidSignatureCounter;
    private final Counter merkleBatchesBuiltCounter;
    private final Counter merkleLeavesCounter;
    private final Counter merkleVerifyFailuresCounter;
    private final java.util.concurrent.atomic.AtomicLong unanchoredEntriesGauge = new java.util.concurrent.atomic.AtomicLong(0);

    public PayFlowMetricsService(MeterRegistry registry,
                                 OutboxEventRepository outboxEventRepository,
                                 @Autowired(required = false) DataSource dataSource) {
        this.registry = registry;

        // 1. Total payment requests
        this.totalPaymentRequestsCounter = Counter.builder("payflow.payments.total")
                .description("Total number of payment transfer requests received")
                .register(registry);

        // 2. Successful and failed payments
        this.successfulPaymentsCounter = Counter.builder("payflow.payments.success")
                .description("Total number of successfully executed payment transfers")
                .register(registry);

        this.failedPaymentsCounter = Counter.builder("payflow.payments.failure")
                .description("Total number of failed payment transfers")
                .register(registry);

        // 3. Payment processing latency
        this.paymentProcessingTimer = Timer.builder("payflow.payments.latency")
                .description("Latency distribution of atomic payment transfers in milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);

        // 4. Idempotency conflicts
        this.idempotencyConflictsCounter = Counter.builder("payflow.idempotency.conflicts")
                .description("Number of idempotency conflicts and duplicate payload rejections")
                .register(registry);

        // 5. Insufficient balance failures
        this.insufficientBalanceCounter = Counter.builder("payflow.payments.insufficient_balance")
                .description("Number of transfers rejected due to insufficient wallet funds")
                .register(registry);

        // 7. Outbox pending event count
        Gauge.builder("payflow.outbox.pending.count", outboxEventRepository,
                repo -> {
                    try {
                        return repo.countByStatus(OutboxStatus.PENDING);
                    } catch (Exception e) {
                        return 0L;
                    }
                })
                .description("Number of pending events waiting in transactional outbox")
                .register(registry);

        // 8. Outbox publishing failures
        this.outboxPublishFailuresCounter = Counter.builder("payflow.outbox.publishing.failures")
                .description("Number of failures during Kafka outbox event publication")
                .register(registry);

        // 9. Redis cache hit/miss
        this.redisCacheHitsCounter = Counter.builder("payflow.redis.cache.hits")
                .description("Number of Redis wallet balance cache hits")
                .register(registry);

        this.redisCacheMissesCounter = Counter.builder("payflow.redis.cache.misses")
                .description("Number of Redis wallet balance cache misses")
                .register(registry);

        // 11. Reconciliation failures
        this.reconciliationFailuresCounter = Counter.builder("payflow.reconciliation.failures")
                .description("Number of financial discrepancies detected by reconciliation engine")
                .register(registry);

        // 12. Fraud Proofs Counters
        this.fraudProofsDetectedCounter = Counter.builder("fraud_proofs_detected_total")
                .description("Total number of offline double-spend fraud proofs detected")
                .register(registry);

        this.fraudProofsRejectedInvalidSignatureCounter = Counter.builder("fraud_proofs_rejected_invalid_signature_total")
                .description("Total number of conflicting receipts rejected due to invalid cryptographic signature")
                .register(registry);

        // Merkle Ledger Metrics
        this.merkleBatchesBuiltCounter = Counter.builder("ledger_merkle_batches_built_total")
                .description("Total number of Merkle ledger batches built and committed")
                .register(registry);

        this.merkleLeavesCounter = Counter.builder("ledger_merkle_leaves_total")
                .description("Total number of ledger entry leaves anchored in Merkle batches")
                .register(registry);

        this.merkleVerifyFailuresCounter = Counter.builder("ledger_merkle_verify_failures_total")
                .description("Total number of Merkle batch or proof verification failures")
                .register(registry);

        Gauge.builder("ledger_unanchored_entries", unanchoredEntriesGauge, java.util.concurrent.atomic.AtomicLong::get)
                .description("Current number of ledger entries awaiting Merkle batch anchoring")
                .register(registry);

        // 10. Database connection pool utilization
        if (dataSource instanceof HikariDataSource hikari) {
            Gauge.builder("payflow.database.pool.utilization", hikari, ds -> {
                try {
                    int max = ds.getMaximumPoolSize();
                    if (max <= 0) return 0.0;
                    if (ds.getHikariPoolMXBean() != null) {
                        return (double) ds.getHikariPoolMXBean().getActiveConnections() / (double) max;
                    }
                    return 0.0;
                } catch (Exception e) {
                    return 0.0;
                }
            })
            .description("Ratio of active HikariCP connections to maximum pool size (0.0 to 1.0)")
            .register(registry);
        }
    }

    public void incrementPaymentRequests() {
        totalPaymentRequestsCounter.increment();
    }

    public void incrementSuccessfulPayments() {
        successfulPaymentsCounter.increment();
    }

    public void incrementFailedPayments() {
        failedPaymentsCounter.increment();
    }

    public <T> T recordPaymentLatency(Callable<T> task) throws Exception {
        return paymentProcessingTimer.recordCallable(task);
    }

    public void recordPaymentLatency(Runnable task) {
        paymentProcessingTimer.record(task);
    }

    public void incrementIdempotencyConflicts() {
        idempotencyConflictsCounter.increment();
    }

    public void incrementInsufficientBalance() {
        insufficientBalanceCounter.increment();
    }

    public void incrementOutboxPublishFailures() {
        outboxPublishFailuresCounter.increment();
    }

    public void incrementRedisCacheHit() {
        redisCacheHitsCounter.increment();
    }

    public void incrementRedisCacheMiss() {
        redisCacheMissesCounter.increment();
    }

    public void incrementReconciliationFailures(int count) {
        reconciliationFailuresCounter.increment(count);
    }

    public void incrementFraudProofsDetected() {
        fraudProofsDetectedCounter.increment();
    }

    public void incrementFraudProofsRejectedInvalidSignature() {
        fraudProofsRejectedInvalidSignatureCounter.increment();
    }

    public void recordReputationTierChange(com.payflow.core.domain.reputation.ReputationTier from,
                                           com.payflow.core.domain.reputation.ReputationTier to) {
        Counter.builder("reputation_tier_changes_total")
                .tag("from", from.name())
                .tag("to", to.name())
                .description("Total number of reputation tier transitions")
                .register(registry)
                .increment();
    }

    public void incrementMerkleBatchesBuilt() {
        merkleBatchesBuiltCounter.increment();
    }

    public void incrementMerkleLeaves(int count) {
        merkleLeavesCounter.increment(count);
    }

    public void incrementMerkleVerifyFailures() {
        merkleVerifyFailuresCounter.increment();
    }

    public void setUnanchoredEntries(long count) {
        unanchoredEntriesGauge.set(count);
    }

    public MeterRegistry getRegistry() {
        return registry;
    }
}
