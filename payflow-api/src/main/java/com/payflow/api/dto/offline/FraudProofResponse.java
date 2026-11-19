package com.payflow.api.dto.offline;

import com.payflow.core.domain.offline.FraudProof;
import com.payflow.core.domain.offline.FraudProofStatus;

import java.time.Instant;
import java.util.UUID;

public record FraudProofResponse(
        UUID id,
        UUID allowanceId,
        String deviceId,
        UUID walletId,
        Long counter,
        UUID firstTxId,
        UUID secondTxId,
        String firstReceiptPayload,
        String firstSignature,
        String secondReceiptPayload,
        String secondSignature,
        FraudProofStatus status,
        Instant detectedAt,
        String reviewedBy,
        Instant reviewedAt,
        String reviewNote
) {
    public static FraudProofResponse from(FraudProof proof) {
        return new FraudProofResponse(
                proof.getId(),
                proof.getAllowance().getId(),
                proof.getDeviceId(),
                proof.getWallet().getId(),
                proof.getCounter(),
                proof.getFirstTxId(),
                proof.getSecondTxId(),
                proof.getFirstReceiptPayload(),
                proof.getFirstSignature(),
                proof.getSecondReceiptPayload(),
                proof.getSecondSignature(),
                proof.getStatus(),
                proof.getDetectedAt(),
                proof.getReviewedBy(),
                proof.getReviewedAt(),
                proof.getReviewNote()
        );
    }
}
