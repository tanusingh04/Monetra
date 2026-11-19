package com.payflow.core.repository;

import com.payflow.core.domain.reputation.UserReputation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserReputationRepository extends JpaRepository<UserReputation, UUID> {

    Optional<UserReputation> findByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM UserReputation r WHERE r.userId = :userId")
    Optional<UserReputation> findByUserIdForUpdate(@Param("userId") UUID userId);
}
