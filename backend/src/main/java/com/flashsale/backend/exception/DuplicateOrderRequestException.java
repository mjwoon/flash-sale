package com.flashsale.backend.exception;

public class DuplicateOrderRequestException extends RuntimeException {

    private static final String ERROR_CODE = "DUPLICATE_ORDER_REQUEST";

    public DuplicateOrderRequestException() {
        super("Duplicate order request detected. The same idempotency key has already been used.");
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
