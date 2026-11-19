package com.payflow.api.service;

import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.domain.outbox.OutboxStatus;
import com.payflow.core.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private OutboxPublisherService outboxPublisherService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(outboxPublisherService, "paymentEventsTopic", "payment-events");
        ReflectionTestUtils.setField(outboxPublisherService, "batchSize", 50);
        ReflectionTestUtils.setField(outboxPublisherService, "maxRetries", 3);
    }

    @Test
    @DisplayName("Should publish pending outbox event to Kafka and mark as PUBLISHED on ACK")
    void publishPendingEvents_Success() {
        OutboxEvent event = new OutboxEvent("PAYMENT", UUID.randomUUID().toString(), "PAYMENT_COMPLETED", "{\"amount\": 100}");
        event.setId(UUID.randomUUID());

        when(outboxEventRepository.fetchPendingEventsForPublishing(50)).thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(eq("payment-events"), eq(event.getAggregateId()), eq(event.getPayload()))).thenReturn(future);

        outboxPublisherService.publishPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        verify(outboxEventRepository).saveAll(any());
    }

    @Test
    @DisplayName("Should increment retry count when Kafka publish fails")
    void publishPendingEvents_KafkaFailure_IncrementsRetry() {
        OutboxEvent event = new OutboxEvent("PAYMENT", UUID.randomUUID().toString(), "PAYMENT_COMPLETED", "{\"amount\": 100}");
        event.setId(UUID.randomUUID());

        when(outboxEventRepository.fetchPendingEventsForPublishing(50)).thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka Broker unavailable"));
        when(kafkaTemplate.send(eq("payment-events"), eq(event.getAggregateId()), eq(event.getPayload()))).thenReturn(failedFuture);

        outboxPublisherService.publishPendingEvents();

        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        verify(outboxEventRepository).saveAll(any());
    }

    @Test
    @DisplayName("Should mark outbox event as FAILED when max retries exceeded")
    void publishPendingEvents_MaxRetriesExceeded_MarksFailed() {
        OutboxEvent event = new OutboxEvent("PAYMENT", UUID.randomUUID().toString(), "PAYMENT_COMPLETED", "{\"amount\": 100}");
        event.setId(UUID.randomUUID());
        event.setRetryCount(2); // After this failure, retryCount becomes 3 (maxRetries)

        when(outboxEventRepository.fetchPendingEventsForPublishing(50)).thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Broker disconnected"));
        when(kafkaTemplate.send(eq("payment-events"), eq(event.getAggregateId()), eq(event.getPayload()))).thenReturn(failedFuture);

        outboxPublisherService.publishPendingEvents();

        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        verify(outboxEventRepository).saveAll(any());
    }
}
