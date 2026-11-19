package com.payflow.core.repository;

import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.domain.outbox.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    long countByStatus(OutboxStatus status);

    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> fetchPendingEventsForPublishing(@Param("limit") int limit);
}
