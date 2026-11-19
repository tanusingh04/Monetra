package com.payflow.api.dto.offline;

import com.payflow.core.domain.offline.AllowanceStatus;
import com.payflow.core.domain.offline.OfflineAllowance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AllowanceCertificateResponse(
        UUID allowanceId,
        UUID walletId,
        String deviceId,
        BigDecimal allocatedAmount,
        BigDecimal spentAmount,
        BigDecimal remainingAmount,
        BigDecimal maxSingleTxAmount,
        Long lastCounter,
        AllowanceStatus status,
        Instant expiresAt,
        String serverSignature,
        String authorityPublicKey
) {
    public static AllowanceCertificateResponse from(OfflineAllowance allowance, String authorityPublicKey) {
        return new AllowanceCertificateResponse(
                allowance.getId(),
                allowance.getWallet().getId(),
                allowance.getDeviceId(),
                allowance.getAllocatedAmount(),
                allowance.getSpentAmount(),
                allowance.getRemainingAllowance(),
                allowance.getMaxSingleTxAmount(),
                allowance.getLastCounter(),
                allowance.getStatus(),
                allowance.getExpiresAt(),
                allowance.getServerSignature(),
                authorityPublicKey
        );
    }
}
