package com.payflow.core.repository;

import com.payflow.core.domain.merkle.LedgerMerkleBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LedgerMerkleBatchRepository extends JpaRepository<LedgerMerkleBatch, Long> {

    Optional<LedgerMerkleBatch> findTopByOrderByBatchNumberDesc();

    List<LedgerMerkleBatch> findTop10ByOrderByBatchNumberDesc();

    Page<LedgerMerkleBatch> findAllByOrderByBatchNumberDesc(Pageable pageable);
}
