package com.payflow.api.service;

import com.payflow.api.dto.wallet.BalanceResponse;
import com.payflow.api.dto.wallet.WalletResponse;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.exception.BusinessException;
import com.payflow.core.exception.ResourceNotFoundException;
import com.payflow.core.repository.LedgerEntryRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private WalletBalanceCacheService walletBalanceCacheService;

    @InjectMocks
    private WalletService walletService;

    private User sampleUser;
    private Wallet sampleWallet;

    @BeforeEach
    void setUp() {
        sampleUser = new User("bob", "bob@payflow.com", "secret", UserRole.ROLE_USER);
        sampleUser.setId(UUID.randomUUID());

        sampleWallet = new Wallet(sampleUser, "INR", BigDecimal.valueOf(5000));
        sampleWallet.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Should successfully fetch user balance")
    void getBalanceByUserId_Success() {
        when(walletRepository.findByUserId(sampleUser.getId())).thenReturn(Optional.of(sampleWallet));

        BalanceResponse balance = walletService.getBalanceByUserId(sampleUser.getId());

        assertThat(balance.walletId()).isEqualTo(sampleWallet.getId());
        assertThat(balance.balance()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(balance.currency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when wallet does not exist")
    void getBalanceByUserId_NotFound() {
        UUID unknownId = UUID.randomUUID();
        when(walletRepository.findByUserId(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getBalanceByUserId(unknownId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Wallet not found");
    }

    @Test
    @DisplayName("Should prevent creating multiple wallets for the same user")
    void createWallet_AlreadyExists_ThrowsException() {
        when(walletRepository.existsByUserId(sampleUser.getId())).thenReturn(true);

        assertThatThrownBy(() -> walletService.createWalletForUser(sampleUser.getId(), "INR"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User already has an active wallet");

        verify(walletRepository, never()).save(any(Wallet.class));
    }
}
