package com.payflow.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.core.event.PaymentCompletedEvent;
import com.payflow.worker.service.EventDeduplicationService;
import com.payflow.worker.service.PaymentEventProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PaymentRetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentRetryConsumer.class);

    private final EventDeduplicationService deduplicationService;
    private final PaymentEventProcessor eventProcessor;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final com.payflow.worker.metrics.PayFlowWorkerMetricsService metricsService;

    @Value("${spring.kafka.consumer.group-id:payflow-worker-group}")
    private String consumerGroup;

    @Value("${app.kafka.topics.payment-retry:payment-retry}")
    private String paymentRetryTopic;

    @Value("${app.kafka.topics.payment-dlq:payment-dlq}")
    private String paymentDlqTopic;

    @Value("${app.outbox.max-retries:3}")
    private int maxRetries = 3;

    public PaymentRetryConsumer(EventDeduplicationService deduplicationService,
                                PaymentEventProcessor eventProcessor,
                                KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper objectMapper,
                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                com.payflow.worker.metrics.PayFlowWorkerMetricsService metricsService) {
        this.deduplicationService = deduplicationService;
        this.eventProcessor = eventProcessor;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    public PaymentRetryConsumer(EventDeduplicationService deduplicationService,
                                PaymentEventProcessor eventProcessor,
                                KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper objectMapper) {
        this(deduplicationService, eventProcessor, kafkaTemplate, objectMapper, null);
    }

    @KafkaListener(
            topics = "${app.kafka.topics.payment-retry:payment-retry}",
            groupId = "${spring.kafka.consumer.group-id:payflow-worker-group}-retry",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeRetry(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        int currentRetry = extractRetryCount(record);
        log.warn("Processing retry attempt {} for record key: {}", currentRetry, record.key());

        PaymentCompletedEvent event;
        try {
            event = objectMapper.readValue(record.value(), PaymentCompletedEvent.class);
        } catch (Exception ex) {
            log.error("Corrupted record in retry topic. Routing to DLQ.", ex);
            forwardToDlq(record, "JSON deserialization failure: " + ex.getMessage());
            acknowledgment.acknowledge();
            return;
        }

        // AUD-06: Real business retry execution instead of sleep/mock
        try {
            eventProcessor.processPaymentEvent(event);
            log.info("Retry attempt {} succeeded for event {}", currentRetry, event.eventId());
            acknowledgment.acknowledge();
        } catch (Exception ex) {
            log.error("Retry attempt {} failed for event {}: {}", currentRetry, event.eventId(), ex.getMessage());
            if (currentRetry >= maxRetries) {
                log.error("Max retries ({}) exhausted for event {}. Routing to Dead Letter Queue (DLQ).", maxRetries, event.eventId());
                if (metricsService != null) metricsService.incrementDlq();
                forwardToDlq(record, "Exhausted " + maxRetries + " retries: " + ex.getMessage());
            } else {
                if (metricsService != null) metricsService.incrementRetried();
                forwardToRetry(record, currentRetry + 1);
            }
            acknowledgment.acknowledge();
        }
    }

    private int extractRetryCount(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("X-Retry-Count");
        if (header != null && header.value() != null) {
            try {
                return Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8));
            } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    private void forwardToRetry(ConsumerRecord<String, String> record, int nextRetryCount) {
        ProducerRecord<String, String> retryRecord = new ProducerRecord<>(paymentRetryTopic, record.key(), record.value());
        retryRecord.headers().add(new RecordHeader("X-Retry-Count", String.valueOf(nextRetryCount).getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(retryRecord);
    }

    private void forwardToDlq(ConsumerRecord<String, String> record, String failureReason) {
        ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(paymentDlqTopic, record.key(), record.value());
        dlqRecord.headers().add(new RecordHeader("X-DLQ-Reason", failureReason.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(dlqRecord);
    }
}
