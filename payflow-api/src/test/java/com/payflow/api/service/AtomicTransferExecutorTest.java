package com.payflow.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.payment.PaymentResponse;
import com.payflow.api.dto.payment.TransferRequest;
import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.exception.InsufficientBalanceException;
import com.payflow.core.repository.OutboxEventRepository;
import com.payflow.core.repository.PaymentRepository;
import com.payflow.core.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AtomicTransferExecutorTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private WalletBalanceCacheService walletBalanceCacheService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private AtomicTransferExecutor atomicTransferExecutor;

    private User senderUser;
    private User receiverUser;
    private Wallet senderWallet;
    private Wallet receiverWallet;

    @BeforeEach
    void setUp() {
        senderUser = new User("alice", "alice@payflow.com", "pass", UserRole.ROLE_USER);
        senderUser.setId(UUID.randomUUID());

        receiverUser = new User("bob", "bob@payflow.com", "pass", UserRole.ROLE_USER);
        receiverUser.setId(UUID.randomUUID());

        senderWallet = new Wallet(senderUser, "INR", BigDecimal.valueOf(10000));
        senderWallet.setId(UUID.randomUUID());

        receiverWallet = new Wallet(receiverUser, "INR", BigDecimal.valueOf(2000));
        receiverWallet.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Should execute atomic transfer, record ledger entries and generate outbox event")
    void executeTransferInTransaction_Success() {
        TransferRequest request = new TransferRequest(senderWallet.getId(), receiverWallet.getId(), BigDecimal.valueOf(3000), "INR");
        String idempotencyKey = "key-exec-1";

        when(walletRepository.findByIdForUpdate(senderWallet.getId())).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByIdForUpdate(receiverWallet.getId())).thenReturn(Optional.of(receiverWallet));

        Payment payment = new Payment(idempotencyKey, senderWallet, receiverWallet, BigDecimal.valueOf(3000), "INR");
        payment.setId(UUID.randomUUID());
        payment.markCompleted();
        when(paymentRepository.save(any(Payment.class))).thenReturn(payment);

        PaymentResponse response = atomicTransferExecutor.executeTransferInTransaction(request, idempotencyKey);

        assertThat(response).isNotNull();
        assertThat(senderWallet.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(7000));
        assertThat(receiverWallet.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(5000));

        verify(ledgerService).recordDoubleEntry(any(Payment.class), eq(senderWallet), eq(receiverWallet), eq(BigDecimal.valueOf(3000).setScale(4)));
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("Should throw InsufficientBalanceException if sender has insufficient funds")
    void executeTransferInTransaction_InsufficientFunds() {
        TransferRequest request = new TransferRequest(senderWallet.getId(), receiverWallet.getId(), BigDecimal.valueOf(15000), "INR");
        String idempotencyKey = "key-exec-2";

        when(walletRepository.findByIdForUpdate(senderWallet.getId())).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByIdForUpdate(receiverWallet.getId())).thenReturn(Optional.of(receiverWallet));

        assertThatThrownBy(() -> atomicTransferExecutor.executeTransferInTransaction(request, idempotencyKey))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient funds");

        verify(paymentRepository, never()).save(any());
        verify(ledgerService, never()).recordDoubleEntry(any(), any(), any(), any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("AUD-03: Should reject cross-currency transfer between INR and USD wallets")
    void executeTransferInTransaction_CrossCurrency_ThrowsException() {
        Wallet usdWallet = new Wallet(receiverUser, "USD", BigDecimal.valueOf(500));
        usdWallet.setId(UUID.randomUUID());

        TransferRequest request = new TransferRequest(senderWallet.getId(), usdWallet.getId(), BigDecimal.valueOf(100), "INR");

        when(walletRepository.findByIdForUpdate(senderWallet.getId())).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByIdForUpdate(usdWallet.getId())).thenReturn(Optional.of(usdWallet));

        assertThatThrownBy(() -> atomicTransferExecutor.executeTransferInTransaction(request, "key-fx-1"))
                .isInstanceOf(com.payflow.core.exception.BusinessException.class)
                .hasMessageContaining("Cross-currency transfers are not supported");
    }

    @Test
    @DisplayName("AUD-03: Should reject transfer when request currency differs from wallet currency")
    void executeTransferInTransaction_RequestCurrencyMismatch_ThrowsException() {
        TransferRequest request = new TransferRequest(senderWallet.getId(), receiverWallet.getId(), BigDecimal.valueOf(100), "USD");

        when(walletRepository.findByIdForUpdate(senderWallet.getId())).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByIdForUpdate(receiverWallet.getId())).thenReturn(Optional.of(receiverWallet));

        assertThatThrownBy(() -> atomicTransferExecutor.executeTransferInTransaction(request, "key-fx-2"))
                .isInstanceOf(com.payflow.core.exception.BusinessException.class)
                .hasMessageContaining("does not match sender wallet currency");
    }
}
