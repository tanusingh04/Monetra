package com.payflow.core.domain.outbox;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEvent.ProcessedEventId.class)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Id
    @Column(name = "consumer_group", nullable = false, length = 100)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt = Instant.now();

    public ProcessedEvent() {}

    public ProcessedEvent(UUID eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public static class ProcessedEventId implements Serializable {
        private UUID eventId;
        private String consumerGroup;

        public ProcessedEventId() {}

        public ProcessedEventId(UUID eventId, String consumerGroup) {
            this.eventId = eventId;
            this.consumerGroup = consumerGroup;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProcessedEventId that = (ProcessedEventId) o;
            return Objects.equals(eventId, that.eventId) && Objects.equals(consumerGroup, that.consumerGroup);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumerGroup);
        }
    }
}
