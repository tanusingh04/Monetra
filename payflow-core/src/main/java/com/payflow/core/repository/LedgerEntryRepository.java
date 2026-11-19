package com.payflow.core.repository;

import com.payflow.core.domain.ledger.EntryType;
import com.payflow.core.domain.ledger.LedgerEntry;
import com.payflow.core.domain.merkle.LedgerMerkleLeaf;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Page<LedgerEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    List<LedgerEntry> findByPaymentId(UUID paymentId);

    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM LedgerEntry l WHERE l.entryType = :entryType")
    BigDecimal sumAmountByEntryType(@Param("entryType") EntryType entryType);

    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM LedgerEntry l WHERE l.wallet.id = :walletId AND l.entryType = :entryType")
    BigDecimal sumAmountByWalletIdAndEntryType(@Param("walletId") UUID walletId, @Param("entryType") EntryType entryType);

    @Query("""
        SELECT e FROM LedgerEntry e
        WHERE NOT EXISTS (
            SELECT 1 FROM LedgerMerkleLeaf l WHERE l.entryId = e.id
        )
        AND e.createdAt <= :cutoffTime
        ORDER BY e.createdAt ASC, e.id ASC
    """)
    List<LedgerEntry> findUnanchoredEntries(@Param("cutoffTime") Instant cutoffTime, Pageable pageable);

    @Query("""
        SELECT COUNT(e) FROM LedgerEntry e
        WHERE NOT EXISTS (
            SELECT 1 FROM LedgerMerkleLeaf l WHERE l.entryId = e.id
        )
    """)
    long countUnanchoredEntries();
}
