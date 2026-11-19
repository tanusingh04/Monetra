package com.payflow.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.core.event.PaymentCompletedEvent;
import com.payflow.worker.service.EventDeduplicationService;
import com.payflow.worker.service.PaymentEventProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private EventDeduplicationService deduplicationService;

    @Mock
    private PaymentEventProcessor eventProcessor;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private Acknowledgment acknowledgment;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private PaymentEventConsumer paymentEventConsumer;

    private PaymentCompletedEvent sampleEvent;
    private String eventJson;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(paymentEventConsumer, "consumerGroup", "payflow-worker-group");
        ReflectionTestUtils.setField(paymentEventConsumer, "paymentRetryTopic", "payment-retry");

        sampleEvent = new PaymentCompletedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "idemp-key-test",
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(2500),
                "INR",
                Instant.now()
        );
        eventJson = objectMapper.writeValueAsString(sampleEvent);
    }

    @Test
    @DisplayName("Should process new event when atomic lock is acquired")
    void consume_NewEvent_Success() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 0, 10L, sampleEvent.paymentId().toString(), eventJson);

        when(deduplicationService.tryAcquireProcessingLock(sampleEvent.eventId(), "payflow-worker-group")).thenReturn(true);

        paymentEventConsumer.consume(record, acknowledgment);

        verify(eventProcessor).processPaymentEvent(sampleEvent);
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("Application-level Deduplication: Should skip processing when lease lock acquisition fails")
    void consume_DuplicateEvent_SkippedWithoutReprocessing() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 0, 15L, sampleEvent.paymentId().toString(), eventJson);

        when(deduplicationService.tryAcquireProcessingLock(sampleEvent.eventId(), "payflow-worker-group")).thenReturn(false);

        paymentEventConsumer.consume(record, acknowledgment);

        // Crucial verification: eventProcessor is never called for duplicate
        verify(eventProcessor, never()).processPaymentEvent(any());
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(kafkaTemplate);
    }
}
