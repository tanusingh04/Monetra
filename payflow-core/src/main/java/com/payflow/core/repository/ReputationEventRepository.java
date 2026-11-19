package com.payflow.core.repository;

import com.payflow.core.domain.reputation.ReputationEvent;
import com.payflow.core.domain.reputation.ReputationEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReputationEventRepository extends JpaRepository<ReputationEvent, UUID> {

    List<ReputationEvent> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<ReputationEvent> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("SELECT COALESCE(SUM(e.scoreDelta), 0) FROM ReputationEvent e WHERE e.user.id = :userId AND e.eventType = :eventType AND e.createdAt >= :since")
    int sumScoreDeltaByUserIdAndEventTypeSince(@Param("userId") UUID userId,
                                               @Param("eventType") ReputationEventType eventType,
                                               @Param("since") Instant since);
}
