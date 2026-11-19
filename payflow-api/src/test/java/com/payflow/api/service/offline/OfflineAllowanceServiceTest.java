package com.payflow.api.service.offline;

import com.payflow.api.dto.offline.AllocateAllowanceRequest;
import com.payflow.api.dto.offline.AllowanceCertificateResponse;
import com.payflow.api.service.WalletBalanceCacheService;
import com.payflow.core.domain.offline.AllowanceStatus;
import com.payflow.core.domain.offline.DeviceEnrollment;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.exception.BusinessException;
import com.payflow.core.exception.InsufficientBalanceException;
import com.payflow.core.repository.DeviceEnrollmentRepository;
import com.payflow.core.repository.OfflineAllowanceRepository;
import com.payflow.core.repository.UserRepository;
import com.payflow.core.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OfflineAllowanceServiceTest {

    @Mock
    private OfflineAllowanceRepository allowanceRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private DeviceEnrollmentRepository deviceEnrollmentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OfflineCryptoService cryptoService;

    @Mock
    private WalletBalanceCacheService walletBalanceCacheService;

    @InjectMocks
    private OfflineAllowanceService allowanceService;

    private User testUser;
    private Wallet testWallet;
    private DeviceEnrollment testDevice;

    @BeforeEach
    void setUp() {
        testUser = new User("alice", "alice@example.com", "hash", UserRole.ROLE_USER);
        testUser.setId(UUID.randomUUID());

        testWallet = new Wallet(testUser, "INR", new BigDecimal("5000.0000"));
        testWallet.setId(UUID.randomUUID());

        testDevice = new DeviceEnrollment(testUser, "device-123", "Pixel 8", "fakePublicKeyBase64");
    }

    @Test
    @DisplayName("Should successfully allocate allowance and reserve wallet balance")
    void shouldAllocateAllowanceSuccessfully() {
        AllocateAllowanceRequest request = new AllocateAllowanceRequest(
                testWallet.getId(), "device-123", new BigDecimal("1500.0000"), new BigDecimal("500.0000"), 24
        );

        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(deviceEnrollmentRepository.findByUserIdAndDeviceId(testUser.getId(), "device-123")).thenReturn(Optional.of(testDevice));
        when(walletRepository.findByIdForUpdate(testWallet.getId())).thenReturn(Optional.of(testWallet));
        when(allowanceRepository.findFirstByWalletIdAndStatusOrderByCreatedAtDesc(testWallet.getId(), AllowanceStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(cryptoService.signAllowanceCertificate(any(), any(), any(), any(), any(), any()))
                .thenReturn("sig-abc-123");
        when(cryptoService.getAuthorityPublicKeyBase64()).thenReturn("auth-pub-key");
        when(allowanceRepository.save(any(OfflineAllowance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AllowanceCertificateResponse response = allowanceService.allocateAllowance(request, testUser.getId());

        assertNotNull(response);
        assertEquals(new BigDecimal("1500.0000"), response.allocatedAmount());
        assertEquals(new BigDecimal("1500.0000"), testWallet.getReservedBalance());
        assertEquals(new BigDecimal("3500.0000"), testWallet.getAvailableBalance());
        verify(walletRepository).save(testWallet);
        verify(allowanceRepository).save(any(OfflineAllowance.class));
    }

    @Test
    @DisplayName("Should reject allocation when single transaction limit exceeds allocated amount")
    void shouldRejectWhenSingleLimitExceedsAllocated() {
        AllocateAllowanceRequest request = new AllocateAllowanceRequest(
                testWallet.getId(), "device-123", new BigDecimal("500.0000"), new BigDecimal("600.0000"), 24
        );

        assertThrows(BusinessException.class, () ->
                allowanceService.allocateAllowance(request, testUser.getId())
        );
    }

    @Test
    @DisplayName("Should reject allocation when wallet has insufficient available funds")
    void shouldRejectWhenInsufficientFunds() {
        AllocateAllowanceRequest request = new AllocateAllowanceRequest(
                testWallet.getId(), "device-123", new BigDecimal("6000.0000"), new BigDecimal("500.0000"), 24
        );

        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(deviceEnrollmentRepository.findByUserIdAndDeviceId(testUser.getId(), "device-123")).thenReturn(Optional.of(testDevice));
        when(walletRepository.findByIdForUpdate(testWallet.getId())).thenReturn(Optional.of(testWallet));

        assertThrows(InsufficientBalanceException.class, () ->
                allowanceService.allocateAllowance(request, testUser.getId())
        );
    }

    @Test
    @DisplayName("Should release unspent reserved funds back to available wallet balance")
    void shouldReleaseUnspentReservedFunds() {
        testWallet.reserveFunds(new BigDecimal("2000.0000"));
        OfflineAllowance allowance = new OfflineAllowance(
                testUser, testWallet, "device-123", new BigDecimal("2000.0000"), new BigDecimal("500.0000"), Instant.now().minus(Duration.ofHours(1))
        );
        allowance.setId(UUID.randomUUID());
        allowance.recordSpent(new BigDecimal("500.0000"), 1L); // 1500 remaining

        when(allowanceRepository.findByIdForUpdate(allowance.getId())).thenReturn(Optional.of(allowance));
        when(walletRepository.findByIdForUpdate(testWallet.getId())).thenReturn(Optional.of(testWallet));
        when(cryptoService.getAuthorityPublicKeyBase64()).thenReturn("auth-pub-key");

        AllowanceCertificateResponse response = allowanceService.releaseAllowance(allowance.getId(), testUser.getId());

        assertNotNull(response);
        assertEquals(AllowanceStatus.EXPIRED, allowance.getStatus());
        assertEquals(new BigDecimal("500.0000"), testWallet.getReservedBalance()); // Only 500 reserved remains
        assertEquals(new BigDecimal("4500.0000"), testWallet.getAvailableBalance());
        verify(walletRepository).save(testWallet);
    }
}
