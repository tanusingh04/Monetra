package com.payflow.core.exception;

/**
 * Thrown when an offline allowance allocation request exceeds the user's reputation tier limit
 * or when a user is restricted from offline operations (maps to HTTP 422).
 */
public class ReputationLimitExceededException extends RuntimeException {

    public ReputationLimitExceededException(String message) {
        super(message);
    }
}
