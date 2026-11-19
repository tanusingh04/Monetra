package com.payflow.core.domain.reputation;

/**
 * Types of immutable events recorded in the append-only reputation audit log.
 */
public enum ReputationEventType {
    INITIAL_ENROLLMENT,
    CLEAN_SETTLEMENT,
    ALLOWANCE_COMPLETED_BONUS,
    SENDER_REJECTION_PENALTY,
    FRAUD_PROOF_RESTRICTION,
    FRAUD_PROOF_CONFIRMED,
    FRAUD_PROOF_DISMISSED_RESTORATION,
    ADMIN_OVERRIDE
}
