package com.payflow.worker.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PaymentDlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentDlqConsumer.class);

    @KafkaListener(
            topics = "${app.kafka.topics.payment-dlq:payment-dlq}",
            groupId = "${spring.kafka.consumer.group-id:payflow-worker-group}-dlq",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeDlq(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String reason = "Unknown";
        Header reasonHeader = record.headers().lastHeader("X-DLQ-Reason");
        if (reasonHeader != null && reasonHeader.value() != null) {
            reason = new String(reasonHeader.value(), StandardCharsets.UTF_8);
        }

        log.error("CRITICAL ALERT - Dead Letter Queue Event Received! Key: {}, Reason: {}, Payload: {}",
                record.key(), reason, record.value());

        // In a production environment, this triggers a PagerDuty / Datadog alert or persists to an incident log
        acknowledgment.acknowledge();
    }
}
