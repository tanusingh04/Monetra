package com.payflow.core.repository;

import com.payflow.core.domain.offline.OfflineTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OfflineTransactionRepository extends JpaRepository<OfflineTransaction, UUID> {

    Optional<OfflineTransaction> findById(UUID id);

    boolean existsById(UUID id);

    boolean existsByAllowanceIdAndCounter(UUID allowanceId, Long counter);

    Optional<OfflineTransaction> findByAllowanceIdAndCounter(UUID allowanceId, Long counter);

    List<OfflineTransaction> findByRejectionReasonStartingWith(String prefix);

    List<OfflineTransaction> findByAllowanceIdOrderByCounterAsc(UUID allowanceId);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(DISTINCT ot.receiverWallet.user.id) FROM OfflineTransaction ot WHERE ot.senderWallet.user.id = :userId AND ot.status = com.payflow.core.domain.offline.OfflineTransactionStatus.SETTLED")
    long countDistinctReceiversBySenderUserId(@org.springframework.data.repository.query.Param("userId") UUID userId);

    boolean existsByAllowanceIdAndStatus(UUID allowanceId, com.payflow.core.domain.offline.OfflineTransactionStatus status);
}
