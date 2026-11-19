package com.payflow.api.dto.offline;

import com.payflow.api.service.offline.FraudProofService;

import java.util.UUID;

public record FraudProofVerificationResponse(
        UUID proofId,
        boolean validProof,
        boolean firstSignatureValid,
        boolean secondSignatureValid,
        Long counter,
        UUID firstTxId,
        UUID secondTxId,
        String deviceId,
        String details
) {
    public static FraudProofVerificationResponse from(FraudProofService.FraudProofVerificationResult result) {
        return new FraudProofVerificationResponse(
                result.proofId(),
                result.isValidProof(),
                result.firstSignatureValid(),
                result.secondSignatureValid(),
                result.counter(),
                result.firstTxId(),
                result.secondTxId(),
                result.deviceId(),
                result.details()
        );
    }
}
