package com.payflow.core.repository;

import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.payment.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT p FROM Payment p WHERE p.senderWallet.id = :walletId OR p.receiverWallet.id = :walletId ORDER BY p.createdAt DESC")
    Page<Payment> findByWalletId(@Param("walletId") UUID walletId, Pageable pageable);

    List<Payment> findByStatus(PaymentStatus status);

    @Query("SELECT p.id FROM Payment p WHERE p.status = com.payflow.core.domain.payment.PaymentStatus.COMPLETED AND " +
           "(SELECT COUNT(l) FROM LedgerEntry l WHERE l.payment.id = p.id) != 2")
    List<UUID> findCompletedPaymentsWithInvalidLedgerCount();
}
