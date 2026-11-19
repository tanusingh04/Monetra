package com.payflow.api.service.offline;

import com.payflow.api.dto.offline.AllocateAllowanceRequest;
import com.payflow.api.dto.offline.AllowanceCertificateResponse;
import com.payflow.api.service.WalletBalanceCacheService;
import com.payflow.core.domain.offline.AllowanceStatus;
import com.payflow.core.domain.offline.DeviceEnrollment;
import com.payflow.core.domain.offline.DeviceStatus;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.domain.wallet.WalletStatus;
import com.payflow.core.exception.BusinessException;
import com.payflow.core.exception.ResourceNotFoundException;
import com.payflow.core.exception.UnauthorizedAccessException;
import com.payflow.core.repository.DeviceEnrollmentRepository;
import com.payflow.core.repository.OfflineAllowanceRepository;
import com.payflow.core.repository.UserRepository;
import com.payflow.core.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class OfflineAllowanceService {

    private static final Logger log = LoggerFactory.getLogger(OfflineAllowanceService.class);

    private final OfflineAllowanceRepository allowanceRepository;
    private final WalletRepository walletRepository;
    private final DeviceEnrollmentRepository deviceEnrollmentRepository;
    private final UserRepository userRepository;
    private final OfflineCryptoService cryptoService;
    private final WalletBalanceCacheService walletBalanceCacheService;
    private final ReputationService reputationService;

    @org.springframework.beans.factory.annotation.Autowired
    public OfflineAllowanceService(OfflineAllowanceRepository allowanceRepository,
                                   WalletRepository walletRepository,
                                   DeviceEnrollmentRepository deviceEnrollmentRepository,
                                   UserRepository userRepository,
                                   OfflineCryptoService cryptoService,
                                   WalletBalanceCacheService walletBalanceCacheService,
                                   @org.springframework.beans.factory.annotation.Autowired(required = false)
                                   ReputationService reputationService) {
        this.allowanceRepository = allowanceRepository;
        this.walletRepository = walletRepository;
        this.deviceEnrollmentRepository = deviceEnrollmentRepository;
        this.userRepository = userRepository;
        this.cryptoService = cryptoService;
        this.walletBalanceCacheService = walletBalanceCacheService;
        this.reputationService = reputationService;
    }

    public OfflineAllowanceService(OfflineAllowanceRepository allowanceRepository,
                                   WalletRepository walletRepository,
                                   DeviceEnrollmentRepository deviceEnrollmentRepository,
                                   UserRepository userRepository,
                                   OfflineCryptoService cryptoService,
                                   WalletBalanceCacheService walletBalanceCacheService) {
        this(allowanceRepository, walletRepository, deviceEnrollmentRepository,
                userRepository, cryptoService, walletBalanceCacheService, null);
    }

    @Transactional
    public AllowanceCertificateResponse allocateAllowance(AllocateAllowanceRequest request, UUID userId) {
        if (request.maxSingleTxAmount().compareTo(request.allocatedAmount()) > 0) {
            throw new BusinessException("Maximum single transaction amount cannot exceed total allocated allowance");
        }

        // Reputation-Based Allowance Limits check (rejects with 422 if exceeds tier limits or restricted)
        if (reputationService != null) {
            reputationService.checkAllowanceRequest(userId, request.allocatedAmount(), request.maxSingleTxAmount());
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        // 1. Verify Device Enrollment
        DeviceEnrollment device = deviceEnrollmentRepository.findByUserIdAndDeviceId(userId, request.deviceId())
                .orElseThrow(() -> new BusinessException("Device " + request.deviceId() + " is not enrolled for this account"));

        if (!device.isActive()) {
            throw new BusinessException("Device " + request.deviceId() + " has been revoked. Re-enrollment required.");
        }

        // 2. Lock Wallet and Verify Available Balance
        Wallet wallet = walletRepository.findByIdForUpdate(request.walletId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + request.walletId()));

        if (!wallet.getUser().getId().equals(userId)) {
            throw new UnauthorizedAccessException("You are not authorized to allocate offline allowance from this wallet");
        }

        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BusinessException("Wallet is not in ACTIVE status: " + wallet.getStatus());
        }

        // Check if there is already an active, non-expired allowance for this device
        Optional<OfflineAllowance> existingActive = allowanceRepository.findFirstByWalletIdAndStatusOrderByCreatedAtDesc(wallet.getId(), AllowanceStatus.ACTIVE);
        if (existingActive.isPresent() && !existingActive.get().isExpired()) {
            throw new BusinessException("An active offline allowance already exists for this wallet. Settle or expire it before requesting a new one.");
        }

        // 3. Atomically Reserve Funds in PostgreSQL
        wallet.reserveFunds(request.allocatedAmount());
        walletRepository.save(wallet);

        // 4. Create Allowance Entity
        int durationHours = request.durationHours() != null ? request.durationHours() : 24;
        Instant expiresAt = Instant.now().plus(Duration.ofHours(durationHours));

        OfflineAllowance allowance = new OfflineAllowance(
                user,
                wallet,
                request.deviceId(),
                request.allocatedAmount(),
                request.maxSingleTxAmount(),
                expiresAt
        );

        // Pre-generate UUID so we can sign over it
        allowance.setId(UUID.randomUUID());

        // 5. Sign with PayFlow Authority ECDSA Key
        String signature = cryptoService.signAllowanceCertificate(
                allowance.getId(),
                wallet.getId(),
                request.deviceId(),
                allowance.getAllocatedAmount(),
                allowance.getMaxSingleTxAmount(),
                expiresAt
        );
        allowance.setServerSignature(signature);

        allowance = allowanceRepository.save(allowance);

        // 6. Post-commit Redis eviction
        registerPostCommitEviction(wallet.getId());

        log.info("Allocated offline allowance {} of {} {} for wallet {} on device {} (expires: {})",
                allowance.getId(), allowance.getAllocatedAmount(), wallet.getCurrency(), wallet.getId(), request.deviceId(), expiresAt);

        return AllowanceCertificateResponse.from(allowance, cryptoService.getAuthorityPublicKeyBase64());
    }

    @Transactional(readOnly = true)
    public Optional<AllowanceCertificateResponse> getActiveAllowance(UUID walletId, UUID userId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));

        if (!wallet.getUser().getId().equals(userId)) {
            throw new UnauthorizedAccessException("Not authorized to view allowances for wallet: " + walletId);
        }

        return allowanceRepository.findFirstByWalletIdAndStatusOrderByCreatedAtDesc(walletId, AllowanceStatus.ACTIVE)
                .filter(a -> !a.isExpired())
                .map(a -> AllowanceCertificateResponse.from(a, cryptoService.getAuthorityPublicKeyBase64()));
    }

    @Transactional
    public AllowanceCertificateResponse releaseAllowance(UUID allowanceId, UUID userId) {
        OfflineAllowance allowance = allowanceRepository.findByIdForUpdate(allowanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Allowance not found: " + allowanceId));

        if (!allowance.getUser().getId().equals(userId)) {
            throw new UnauthorizedAccessException("Not authorized to release allowance: " + allowanceId);
        }

        if (allowance.getStatus() == AllowanceStatus.SETTLED || allowance.getStatus() == AllowanceStatus.EXPIRED) {
            log.info("Allowance {} is already settled/expired", allowanceId);
            return AllowanceCertificateResponse.from(allowance, cryptoService.getAuthorityPublicKeyBase64());
        }

        Wallet wallet = walletRepository.findByIdForUpdate(allowance.getWallet().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + allowance.getWallet().getId()));

        BigDecimal unspent = allowance.getRemainingAllowance();
        if (unspent.compareTo(BigDecimal.ZERO) > 0) {
            wallet.releaseReservedFunds(unspent);
            walletRepository.save(wallet);
            log.info("Released {} unspent reserved funds back to available balance of wallet {}", unspent, wallet.getId());
        }

        if (allowance.isExpired()) {
            allowance.markExpired();
        } else {
            allowance.markSettled();
        }
        allowanceRepository.save(allowance);

        if (reputationService != null) {
            reputationService.checkAndAwardAllowanceCompletedBonus(allowance);
        }

        registerPostCommitEviction(wallet.getId());

        return AllowanceCertificateResponse.from(allowance, cryptoService.getAuthorityPublicKeyBase64());
    }

    private void registerPostCommitEviction(UUID walletId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    walletBalanceCacheService.evictBalance(walletId);
                }
            });
        } else {
            walletBalanceCacheService.evictBalance(walletId);
        }
    }
}
