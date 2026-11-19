package com.payflow.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationSeverity;
import com.payflow.worker.alert.AlertRuleEngine;
import com.payflow.worker.alert.channel.AlertChannel;
import com.payflow.worker.metrics.PayFlowWorkerMetricsService;
import com.payflow.worker.service.EventDeduplicationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class FraudAlertConsumerTest {

    @Mock
    private EventDeduplicationService deduplicationService;

    @Mock
    private AlertRuleEngine ruleEngine;

    @Mock
    private AlertChannel alertChannel;

    @Mock
    private Acknowledgment acknowledgment;

    @Mock
    private PayFlowWorkerMetricsService metricsService;

    private ObjectMapper objectMapper;
    private FraudAlertConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        consumer = new FraudAlertConsumer(
                deduplicationService, ruleEngine, List.of(alertChannel), objectMapper, metricsService
        );
    }

    @Test
    @DisplayName("Consumer processes event and delivers notifications on first delivery")
    void testConsumeFirstDelivery() {
        UUID eventId = UUID.randomUUID();
        String json = String.format("{\"proofId\":\"%s\",\"secondReceiptSettled\":true,\"counter\":1}", eventId);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("fraud-alerts", 0, 10L, eventId.toString(), json);

        when(deduplicationService.tryAcquireProcessingLock(eq(eventId), any())).thenReturn(true);
        Notification notification = new Notification(
                eventId, "OFFLINE_FRAUD_PROOF_DETECTED", NotificationSeverity.CRITICAL,
                null, "ROLE_ADMIN", "Title", "Msg", "FRAUD_PROOF", eventId.toString()
        );
        when(ruleEngine.evaluateAlertRules(any(), eq(json))).thenReturn(List.of(notification));

        consumer.consume(record, acknowledgment);

        verify(ruleEngine).evaluateAlertRules(eq("OFFLINE_FRAUD_PROOF_DETECTED"), eq(json));
        verify(alertChannel).deliver(notification);
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Consumer suppresses duplicate event deliveries using deduplication lease")
    void testDuplicateEventSuppression() {
        UUID eventId = UUID.randomUUID();
        String json = String.format("{\"proofId\":\"%s\",\"secondReceiptSettled\":true,\"counter\":1}", eventId);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("fraud-alerts", 0, 11L, eventId.toString(), json);

        // Deduplication lease returns false (already processed)
        when(deduplicationService.tryAcquireProcessingLock(eq(eventId), any())).thenReturn(false);

        consumer.consume(record, acknowledgment);

        verifyNoInteractions(ruleEngine);
        verifyNoInteractions(alertChannel);
        verify(metricsService).incrementDuplicates();
        verify(acknowledgment).acknowledge();
    }
}
