package com.payflow.core.repository;

import com.payflow.core.domain.outbox.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.ProcessedEventId> {
    boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);
}
