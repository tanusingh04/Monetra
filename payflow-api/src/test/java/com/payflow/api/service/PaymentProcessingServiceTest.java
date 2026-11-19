package com.payflow.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.payment.PaymentResponse;
import com.payflow.api.dto.payment.TransferRequest;
import com.payflow.core.domain.idempotency.IdempotencyRecord;
import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.exception.BusinessException;
import com.payflow.core.exception.InsufficientBalanceException;
import com.payflow.core.exception.UnauthorizedAccessException;
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
class PaymentProcessingServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private AtomicTransferExecutor atomicTransferExecutor;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private PaymentProcessingService paymentProcessingService;

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
    @DisplayName("Should successfully transfer ₹3,000 from Alice (₹10,000) to Bob (₹2,000)")
    void transfer_Success() {
        TransferRequest request = new TransferRequest(senderWallet.getId(), receiverWallet.getId(), BigDecimal.valueOf(3000), "INR");
        String idempotencyKey = UUID.randomUUID().toString();

        when(walletRepository.findById(senderWallet.getId())).thenReturn(Optional.of(senderWallet));
        when(idempotencyService.computePayloadHash(request)).thenReturn("hash-123");
        when(idempotencyService.lockOrCreateKey(idempotencyKey, "hash-123")).thenReturn(Optional.empty());

        Payment mockPayment = new Payment(idempotencyKey, senderWallet, receiverWallet, BigDecimal.valueOf(3000), "INR");
        mockPayment.setId(UUID.randomUUID());
        mockPayment.markCompleted();
        PaymentResponse expectedResponse = PaymentResponse.from(mockPayment);

        when(atomicTransferExecutor.executeTransferInTransaction(request, idempotencyKey)).thenReturn(expectedResponse);

        PaymentResponse response = paymentProcessingService.transfer(request, idempotencyKey, senderUser.getId());

        assertThat(response).isNotNull();
        assertThat(response.amount()).isEqualByComparingTo(BigDecimal.valueOf(3000));
        verify(atomicTransferExecutor).executeTransferInTransaction(request, idempotencyKey);
        verify(idempotencyService).markCompleted(eq(idempotencyKey), eq(200), any(PaymentResponse.class), eq(mockPayment.getId()));
    }

    @Test
    @DisplayName("Should throw InsufficientBalanceException when sender does not have enough funds")
    void transfer_InsufficientBalance() {
        TransferRequest request = new TransferRequest(senderWallet.getId(), receiverWallet.getId(), BigDecimal.valueOf(15000), "INR");
        String idempotencyKey = UUID.randomUUID().toString();

        when(walletRepository.findById(senderWallet.getId())).thenReturn(Optional.of(senderWallet));
        when(idempotencyService.computePayloadHash(request)).thenReturn("hash-123");
        when(idempotencyService.lockOrCreateKey(idempotencyKey, "hash-123")).thenReturn(Optional.empty());

        when(atomicTransferExecutor.executeTransferInTransaction(request, idempotencyKey))
                .thenThrow(new InsufficientBalanceException("Insufficient funds"));

        assertThatThrownBy(() -> paymentProcessingService.transfer(request, idempotencyKey, senderUser.getId()))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient funds");

        verify(idempotencyService).markFailed(eq(idempotencyKey), eq(422), anyString());
    }

    @Test
    @DisplayName("Should reject transfer if sender and receiver wallets are the same")
    void transfer_SameWallet_ThrowsException() {
        TransferRequest request = new TransferRequest(senderWallet.getId(), senderWallet.getId(), BigDecimal.valueOf(100), "INR");

        assertThatThrownBy(() -> paymentProcessingService.transfer(request, "key-1", senderUser.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Sender and receiver wallets must be different");
    }

    @Test
    @DisplayName("Should reject transfer if authenticated user does not own the sender wallet")
    void transfer_UnauthorizedSender_ThrowsException() {
        TransferRequest request = new TransferRequest(senderWallet.getId(), receiverWallet.getId(), BigDecimal.valueOf(100), "INR");
        when(walletRepository.findById(senderWallet.getId())).thenReturn(Optional.of(senderWallet));

        UUID anotherUserId = UUID.randomUUID();
        assertThatThrownBy(() -> paymentProcessingService.transfer(request, "key-1", anotherUserId))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessageContaining("not authorized");
    }

    @Test
    @DisplayName("Should return cached response on repeated request with same idempotency key")
    void transfer_IdempotentRetry_ReturnsCachedResponse() throws Exception {
        TransferRequest request = new TransferRequest(senderWallet.getId(), receiverWallet.getId(), BigDecimal.valueOf(500), "INR");
        String idempotencyKey = "key-repeat";

        when(walletRepository.findById(senderWallet.getId())).thenReturn(Optional.of(senderWallet));
        when(idempotencyService.computePayloadHash(request)).thenReturn("hash-500");

        PaymentResponse cachedResponse = new PaymentResponse(
                UUID.randomUUID(), idempotencyKey, senderWallet.getId(), receiverWallet.getId(),
                BigDecimal.valueOf(500), "INR", "COMPLETED", null, null
        );
        IdempotencyRecord record = new IdempotencyRecord(idempotencyKey, "hash-500");
        record.markCompleted(200, objectMapper.writeValueAsString(cachedResponse), cachedResponse.paymentId());

        when(idempotencyService.lockOrCreateKey(idempotencyKey, "hash-500")).thenReturn(Optional.of(record));

        PaymentResponse result = paymentProcessingService.transfer(request, idempotencyKey, senderUser.getId());

        assertThat(result.paymentId()).isEqualTo(cachedResponse.paymentId());
        assertThat(result.amount()).isEqualByComparingTo(BigDecimal.valueOf(500));

        // Ensure transfer logic was NOT executed again
        verify(atomicTransferExecutor, never()).executeTransferInTransaction(any(), any());
    }

    @Test
    @DisplayName("AUD-04: Should cleanly replay failure exception when retrying previously failed idempotent request")
    void transfer_IdempotentRetry_PreviouslyFailed_ReplaysFailureWithoutCrash() {
        TransferRequest request = new TransferRequest(senderWallet.getId(), receiverWallet.getId(), BigDecimal.valueOf(500), "INR");
        String idempotencyKey = "key-failed-retry";

        when(walletRepository.findById(senderWallet.getId())).thenReturn(Optional.of(senderWallet));
        when(idempotencyService.computePayloadHash(request)).thenReturn("hash-500");

        IdempotencyRecord failedRecord = new IdempotencyRecord(idempotencyKey, "hash-500");
        failedRecord.markFailed(422, "Insufficient funds in wallet");

        when(idempotencyService.lockOrCreateKey(idempotencyKey, "hash-500")).thenReturn(Optional.of(failedRecord));

        assertThatThrownBy(() -> paymentProcessingService.transfer(request, idempotencyKey, senderUser.getId()))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient funds in wallet");

        verify(atomicTransferExecutor, never()).executeTransferInTransaction(any(), any());
    }
}
