package com.payflow.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.core.event.PaymentCompletedEvent;
import com.payflow.worker.service.EventDeduplicationService;
import com.payflow.worker.service.PaymentEventProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
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
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final EventDeduplicationService deduplicationService;
    private final PaymentEventProcessor eventProcessor;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final com.payflow.worker.metrics.PayFlowWorkerMetricsService metricsService;

    @Value("${spring.kafka.consumer.group-id:payflow-worker-group}")
    private String consumerGroup;

    @Value("${app.kafka.topics.payment-retry:payment-retry}")
    private String paymentRetryTopic;

    public PaymentEventConsumer(EventDeduplicationService deduplicationService,
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

    public PaymentEventConsumer(EventDeduplicationService deduplicationService,
                                PaymentEventProcessor eventProcessor,
                                KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper objectMapper) {
        this(deduplicationService, eventProcessor, kafkaTemplate, objectMapper, null);
    }

    @KafkaListener(
            topics = "${app.kafka.topics.payment-events:payment-events}",
            groupId = "${spring.kafka.consumer.group-id:payflow-worker-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        log.info("Received payment event from topic: {}, partition: {}, offset: {}",
                record.topic(), record.partition(), record.offset());

        PaymentCompletedEvent event;
        try {
            event = objectMapper.readValue(record.value(), PaymentCompletedEvent.class);
        } catch (Exception ex) {
            log.error("Failed to parse PaymentCompletedEvent payload: {}. Skipping unparseable record.", record.value(), ex);
            acknowledgment.acknowledge();
            return;
        }

        try {
            org.slf4j.MDC.put("correlationId", event.eventId().toString());
            org.slf4j.MDC.put("paymentId", event.paymentId().toString());

            // 1. Atomic Deduplication Lock Acquisition (AUD-05)
            if (!deduplicationService.tryAcquireProcessingLock(event.eventId(), consumerGroup)) {
                log.warn("Application Deduplication: Event {} has already been claimed or processed by {}. Acknowledging duplicate.",
                        event.eventId(), consumerGroup);
                if (metricsService != null) metricsService.incrementDuplicates();
                acknowledgment.acknowledge();
                return;
            }

            // 2. Business Processing (Notification, Webhook, Analytics)
            try {
                eventProcessor.processPaymentEvent(event);
                if (metricsService != null) metricsService.incrementProcessed();
                acknowledgment.acknowledge();
                log.info("Successfully processed payment event {} for payment {}", event.eventId(), event.paymentId());
            } catch (Exception ex) {
                log.error("Error processing payment event {}: {}. Forwarding to retry topic.", event.eventId(), ex.getMessage());
                if (metricsService != null) metricsService.incrementRetried();
                forwardToRetry(record, 1);
                acknowledgment.acknowledge();
            }
        } finally {
            org.slf4j.MDC.remove("correlationId");
            org.slf4j.MDC.remove("paymentId");
        }
    }

    private void forwardToRetry(ConsumerRecord<String, String> record, int retryCount) {
        ProducerRecord<String, String> retryRecord = new ProducerRecord<>(paymentRetryTopic, record.key(), record.value());
        retryRecord.headers().add(new RecordHeader("X-Retry-Count", String.valueOf(retryCount).getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(retryRecord);
        log.info("Forwarded event key {} to retry topic with retry count {}", record.key(), retryCount);
    }
}
