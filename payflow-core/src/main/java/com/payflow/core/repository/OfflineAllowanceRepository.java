package com.payflow.core.repository;

import com.payflow.core.domain.offline.AllowanceStatus;
import com.payflow.core.domain.offline.OfflineAllowance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OfflineAllowanceRepository extends JpaRepository<OfflineAllowance, UUID> {

    Optional<OfflineAllowance> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM OfflineAllowance a WHERE a.id = :id")
    Optional<OfflineAllowance> findByIdForUpdate(@Param("id") UUID id);

    List<OfflineAllowance> findByUserIdAndStatus(UUID userId, AllowanceStatus status);

    Optional<OfflineAllowance> findFirstByWalletIdAndStatusOrderByCreatedAtDesc(UUID walletId, AllowanceStatus status);

    List<OfflineAllowance> findByStatusAndExpiresAtBefore(AllowanceStatus status, Instant cutoff);
}
