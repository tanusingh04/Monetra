package com.payflow.core.repository;

import com.payflow.core.domain.offline.OfflineRejectedReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OfflineRejectedReceiptRepository extends JpaRepository<OfflineRejectedReceipt, UUID> {
    boolean existsByTxId(UUID txId);
    boolean existsByAllowanceId(UUID allowanceId);
}
