package com.payflow.api.service;

import com.payflow.api.dto.wallet.BalanceResponse;
import com.payflow.api.dto.wallet.TransactionHistoryResponse;
import com.payflow.api.dto.wallet.WalletResponse;
import com.payflow.core.domain.ledger.LedgerEntry;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.exception.BusinessException;
import com.payflow.core.exception.ResourceNotFoundException;
import com.payflow.core.repository.LedgerEntryRepository;
import com.payflow.core.repository.UserRepository;
import com.payflow.core.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletBalanceCacheService walletBalanceCacheService;

    public WalletService(WalletRepository walletRepository,
                         UserRepository userRepository,
                         LedgerEntryRepository ledgerEntryRepository,
                         WalletBalanceCacheService walletBalanceCacheService) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletBalanceCacheService = walletBalanceCacheService;
    }

    @Transactional
    public WalletResponse createWalletForUser(UUID userId, String currency) {
        if (walletRepository.existsByUserId(userId)) {
            throw new BusinessException("User already has an active wallet");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        String walletCurrency = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "INR";
        Wallet wallet = new Wallet(user, walletCurrency, BigDecimal.ZERO);
        wallet = walletRepository.save(wallet);

        log.info("Created wallet {} for user {}", wallet.getId(), userId);
        return WalletResponse.from(wallet);
    }

    @Transactional(readOnly = true)
    public WalletResponse getWalletByUserId(UUID userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user id: " + userId));
        return WalletResponse.from(wallet);
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalanceByUserId(UUID userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user id: " + userId));

        // Cache-aside pattern: check Redis, fallback to DB & populate Redis
        BigDecimal balance = walletBalanceCacheService.getCachedBalance(wallet.getId())
                .orElseGet(() -> {
                    walletBalanceCacheService.cacheBalance(wallet.getId(), wallet.getBalance());
                    return wallet.getBalance();
                });

        return new BalanceResponse(wallet.getId(), balance, wallet.getCurrency());
    }

    @Transactional(readOnly = true)
    public Page<TransactionHistoryResponse> getTransactionHistory(UUID userId, Pageable pageable) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user id: " + userId));

        Page<LedgerEntry> entries = ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId(), pageable);
        return entries.map(TransactionHistoryResponse::from);
    }

    @Transactional(readOnly = true)
    public Wallet getWalletEntityById(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + walletId));
    }
}
