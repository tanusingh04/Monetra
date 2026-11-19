package com.payflow.core.exception;

public class ConcurrentRequestException extends BusinessException {
    public ConcurrentRequestException(String message) {
        super(message);
    }
}
