package com.payflow.api.service;

import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final com.payflow.api.metrics.PayFlowMetricsService metricsService;

    @Value("${app.kafka.topics.payment-events:payment-events}")
    private String paymentEventsTopic;

    @Value("${app.kafka.topics.fraud-alerts:fraud-alerts}")
    private String fraudAlertsTopic;

    @Value("${app.outbox.batch-size:50}")
    private int batchSize = 50;

    @Value("${app.outbox.max-retries:3}")
    private int maxRetries = 3;

    @Value("${app.outbox.publish-timeout-sec:5}")
    private int publishTimeoutSeconds = 5;

    public OutboxPublisherService(OutboxEventRepository outboxEventRepository,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false)
                                  com.payflow.api.metrics.PayFlowMetricsService metricsService) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.metricsService = metricsService;
    }

    @Scheduled(fixedDelayString = "${app.outbox.polling-rate-ms:2000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.fetchPendingEventsForPublishing(batchSize);

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Found {} pending outbox events for publishing", pendingEvents.size());

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (OutboxEvent event : pendingEvents) {
            String topic = selectTopicForEvent(event);
            CompletableFuture<Void> future = kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                    .thenAccept(result -> {
                        event.markPublished();
                        log.debug("Broker acknowledged outbox event {} on topic {}", event.getId(), topic);
                    })
                    .exceptionally(ex -> {
                        log.error("Kafka delivery failure for outbox event {}: {}", event.getId(), ex.getMessage());
                        handlePublishFailure(event);
                        return null;
                    });
            futures.add(future);
        }

        // Wait for all messages in the batch to be acknowledged by the Kafka broker before committing transaction
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(publishTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("Batch publishing encountered timeout or broker latency: {}", ex.getMessage());
            for (OutboxEvent event : pendingEvents) {
                if (event.getStatus() != com.payflow.core.domain.outbox.OutboxStatus.PUBLISHED) {
                    handlePublishFailure(event);
                }
            }
        }

        // Persist updated statuses (PUBLISHED or retry increment) within the current transaction
        outboxEventRepository.saveAll(pendingEvents);
    }

    private String selectTopicForEvent(OutboxEvent event) {
        String eventType = event.getEventType();
        String aggregateType = event.getAggregateType();

        if ("OFFLINE_FRAUD_PROOF_DETECTED".equals(eventType) ||
            "OFFLINE_RECEIPT_PROTECTED".equals(eventType) ||
            "OFFLINE_RECEIPT_UNCOVERED".equals(eventType) ||
            "REPUTATION_TIER_CHANGED".equals(eventType) ||
            "LEDGER_ROOT_VERIFICATION_FAILED".equals(eventType) ||
            "OFFLINE_FRAUD_PROOF".equals(aggregateType) ||
            "USER_REPUTATION".equals(aggregateType)) {
            return fraudAlertsTopic;
        }
        return paymentEventsTopic;
    }

    private void handlePublishFailure(OutboxEvent event) {
        if (metricsService != null) {
            metricsService.incrementOutboxPublishFailures();
        }
        event.incrementRetry();
        if (event.getRetryCount() >= maxRetries) {
            event.markFailed();
            log.error("Outbox event {} exceeded maximum retries ({}). Marked as FAILED.", event.getId(), maxRetries);
        }
    }
}
