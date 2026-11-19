package com.payflow.worker.service;

import com.payflow.core.domain.outbox.ProcessedEvent;
import com.payflow.core.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class EventDeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(EventDeduplicationService.class);

    private final ProcessedEventRepository processedEventRepository;

    public EventDeduplicationService(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional(readOnly = true)
    public boolean isAlreadyProcessed(UUID eventId, String consumerGroup) {
        return processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup);
    }

    @Transactional
    public boolean markAsProcessed(UUID eventId, String consumerGroup) {
        try {
            ProcessedEvent record = new ProcessedEvent(eventId, consumerGroup);
            processedEventRepository.saveAndFlush(record);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent duplicate detected for event {} in group {}", eventId, consumerGroup);
            return false;
        }
    }

    /**
     * Atomically acquires a processing lease by persisting the event ID before executing side-effects.
     * Eliminates the check-then-act race condition (AUD-05).
     */
    @Transactional
    public boolean tryAcquireProcessingLock(UUID eventId, String consumerGroup) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup)) {
            return false;
        }
        try {
            ProcessedEvent record = new ProcessedEvent(eventId, consumerGroup);
            processedEventRepository.saveAndFlush(record);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent consumer lease collision for event {} in group {}. Skipping duplicate.", eventId, consumerGroup);
            return false;
        }
    }
}
