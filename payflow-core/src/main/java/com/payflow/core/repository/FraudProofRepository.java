package com.payflow.core.repository;

import com.payflow.core.domain.offline.FraudProof;
import com.payflow.core.domain.offline.FraudProofStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FraudProofRepository extends JpaRepository<FraudProof, UUID> {

    Optional<FraudProof> findByAllowanceIdAndCounterAndFirstTxIdAndSecondTxId(
            UUID allowanceId, Long counter, UUID firstTxId, UUID secondTxId);

    Optional<FraudProof> findFirstByAllowanceIdAndCounter(UUID allowanceId, Long counter);

    Page<FraudProof> findByStatus(FraudProofStatus status, Pageable pageable);

    boolean existsByAllowanceId(UUID allowanceId);

    List<FraudProof> findByAllowanceId(UUID allowanceId);

    List<FraudProof> findByAllowanceUserId(UUID userId);

    List<FraudProof> findByStatus(FraudProofStatus status);
}
