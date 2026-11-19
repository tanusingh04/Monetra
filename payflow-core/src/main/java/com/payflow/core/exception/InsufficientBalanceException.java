package com.payflow.core.exception;

public class InsufficientBalanceException extends BusinessException {
    public InsufficientBalanceException(String message) {
        super(message);
    }
}
