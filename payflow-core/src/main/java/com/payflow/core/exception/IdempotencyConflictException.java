package com.payflow.core.exception;

public class IdempotencyConflictException extends BusinessException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
