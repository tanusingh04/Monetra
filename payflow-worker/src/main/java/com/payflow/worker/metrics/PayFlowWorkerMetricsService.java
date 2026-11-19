package com.payflow.worker.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker-specific metrics service tracking Kafka consumption rate, consumer lag,
 * duplicate suppression, retry cycles, and dead-letter queue routing.
 */
@Service
public class PayFlowWorkerMetricsService {

    private final MeterRegistry registry;
    private final Counter eventsProcessedCounter;
    private final Counter duplicateEventsCounter;
    private final Counter retryEventsCounter;
    private final Counter dlqEventsCounter;
    private final AtomicLong estimatedConsumerLag = new AtomicLong(0);

    public PayFlowWorkerMetricsService(MeterRegistry registry) {
        this.registry = registry;

        this.eventsProcessedCounter = Counter.builder("payflow.worker.events.processed")
                .description("Total number of payment events successfully processed by worker")
                .register(registry);

        this.duplicateEventsCounter = Counter.builder("payflow.worker.events.duplicates")
                .description("Total number of duplicate Kafka events suppressed by worker deduplication")
                .register(registry);

        this.retryEventsCounter = Counter.builder("payflow.worker.events.retried")
                .description("Total number of events routed through retry consumer")
                .register(registry);

        this.dlqEventsCounter = Counter.builder("payflow.worker.events.dlq")
                .description("Total number of permanently failing events routed to Dead Letter Queue (DLQ)")
                .register(registry);

        // 6. Kafka consumer lag
        Gauge.builder("payflow.kafka.consumer.lag", estimatedConsumerLag, AtomicLong::get)
                .description("Estimated Kafka consumer lag for payflow consumer groups")
                .register(registry);
    }

    public void incrementProcessed() {
        eventsProcessedCounter.increment();
    }

    public void incrementDuplicates() {
        duplicateEventsCounter.increment();
    }

    public void incrementRetried() {
        retryEventsCounter.increment();
    }

    public void incrementDlq() {
        dlqEventsCounter.increment();
    }

    public void incrementAlertCreated(String type, String severity) {
        Counter.builder("alerts_created_total")
                .tag("type", type)
                .tag("severity", severity)
                .description("Total number of fraud alerts and notifications created")
                .register(registry)
                .increment();
    }

    public void incrementAlertDeliveryFailed(String channel) {
        Counter.builder("alerts_delivery_failed_total")
                .tag("channel", channel)
                .description("Total number of alert delivery failures by channel")
                .register(registry)
                .increment();
    }

    public void updateConsumerLag(long lag) {
        estimatedConsumerLag.set(Math.max(0, lag));
    }

    public MeterRegistry getRegistry() {
        return registry;
    }
}
