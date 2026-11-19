package com.payflow.worker.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.core.domain.notification.Notification;
import com.payflow.worker.alert.AlertRuleEngine;
import com.payflow.worker.alert.channel.AlertChannel;
import com.payflow.worker.metrics.PayFlowWorkerMetricsService;
import com.payflow.worker.service.EventDeduplicationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class FraudAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudAlertConsumer.class);

    private final EventDeduplicationService deduplicationService;
    private final AlertRuleEngine ruleEngine;
    private final List<AlertChannel> channels;
    private final ObjectMapper objectMapper;
    private final PayFlowWorkerMetricsService metricsService;

    @Value("${spring.kafka.consumer.group-id:payflow-worker-group}")
    private String consumerGroup = "payflow-worker-group";

    public FraudAlertConsumer(EventDeduplicationService deduplicationService,
                              AlertRuleEngine ruleEngine,
                              List<AlertChannel> channels,
                              ObjectMapper objectMapper,
                              @Autowired(required = false) PayFlowWorkerMetricsService metricsService) {
        this.deduplicationService = deduplicationService;
        this.ruleEngine = ruleEngine;
        this.channels = channels;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.fraud-alerts:fraud-alerts}",
            groupId = "${spring.kafka.consumer.group-id:payflow-worker-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        log.info("Received fraud alert event from topic: {}, partition: {}, offset: {}",
                record.topic(), record.partition(), record.offset());

        UUID eventId = null;
        String eventType = null;

        try {
            JsonNode root = objectMapper.readTree(record.value());
            if (root.has("eventId")) {
                eventId = UUID.fromString(root.get("eventId").asText());
            } else if (root.has("proofId")) {
                eventId = UUID.fromString(root.get("proofId").asText());
            } else {
                try {
                    eventId = UUID.fromString(record.key());
                } catch (Exception ignored) {
                    eventId = UUID.randomUUID();
                }
            }

            // Infer event type if not in headers
            if (root.has("secondReceiptSettled") || root.has("counter")) {
                eventType = "OFFLINE_FRAUD_PROOF_DETECTED";
            } else if (root.has("protectionStatus")) {
                eventType = "OFFLINE_RECEIPT_PROTECTED";
            } else if (root.has("uncoveredAmount")) {
                eventType = "OFFLINE_RECEIPT_UNCOVERED";
            } else if (root.has("newTier")) {
                eventType = "REPUTATION_TIER_CHANGED";
            } else if (root.has("ledgerRoot") || root.has("actualRoot")) {
                eventType = "LEDGER_ROOT_VERIFICATION_FAILED";
            } else {
                eventType = "UNKNOWN_ALERT_EVENT";
            }
        } catch (Exception ex) {
            log.error("Failed to parse alert event JSON payload: {}", record.value(), ex);
            acknowledgment.acknowledge();
            return;
        }

        try {
            org.slf4j.MDC.put("correlationId", eventId.toString());

            // 1. Deduplication using EventDeduplicationService
            if (!deduplicationService.tryAcquireProcessingLock(eventId, consumerGroup)) {
                log.warn("Application Deduplication: Alert event {} already processed by {}. Acknowledging duplicate.",
                        eventId, consumerGroup);
                if (metricsService != null) metricsService.incrementDuplicates();
                acknowledgment.acknowledge();
                return;
            }

            // 2. Evaluate Alert Rules
            List<Notification> notifications = ruleEngine.evaluateAlertRules(eventType, record.value());

            // 3. Dispatch to Delivery Channels
            for (Notification notification : notifications) {
                for (AlertChannel channel : channels) {
                    try {
                        channel.deliver(notification);
                    } catch (Exception ex) {
                        log.error("Error delivering notification {} via channel {}: {}",
                                notification.getId(), channel.getChannel(), ex.getMessage(), ex);
                    }
                }
            }

            if (metricsService != null) {
                metricsService.incrementProcessed();
            }
            log.info("Successfully processed fraud alert event {} (generated {} notifications)",
                    eventId, notifications.size());
            acknowledgment.acknowledge();
        } finally {
            org.slf4j.MDC.remove("correlationId");
        }
    }
}
