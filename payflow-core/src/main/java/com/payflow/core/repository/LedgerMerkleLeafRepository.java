package com.payflow.core.repository;

import com.payflow.core.domain.merkle.LedgerMerkleLeaf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerMerkleLeafRepository extends JpaRepository<LedgerMerkleLeaf, UUID> {

    Optional<LedgerMerkleLeaf> findByEntryId(UUID entryId);

    List<LedgerMerkleLeaf> findAllByBatchNumberOrderByLeafIndexAsc(Long batchNumber);

    long countByBatchNumber(Long batchNumber);

    boolean existsByEntryId(UUID entryId);
}
