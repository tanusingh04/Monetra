package com.payflow.core.repository;

import com.payflow.core.domain.offline.OfflineProtectedSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OfflineProtectedSettlementRepository extends JpaRepository<OfflineProtectedSettlement, UUID> {
    List<OfflineProtectedSettlement> findByFraudProofId(UUID proofId);
    boolean existsByTxId(UUID txId);
}
