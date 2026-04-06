package com.flashsale.backend.exception;

public class InsufficientStockException extends RuntimeException {

    private static final String ERROR_CODE = "OUT_OF_STOCK";

    public InsufficientStockException(int requested, long available) {
        super("Insufficient stock. Requested: " + requested + ", Available: " + available);
    }

    public InsufficientStockException(String message) {
        super(message);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
