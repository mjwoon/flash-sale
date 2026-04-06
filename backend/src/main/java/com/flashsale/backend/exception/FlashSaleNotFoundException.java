package com.flashsale.backend.exception;

public class FlashSaleNotFoundException extends RuntimeException {

    private static final String ERROR_CODE = "SALE_EVENT_NOT_FOUND";

    public FlashSaleNotFoundException(Long id) {
        super("Sale event not found with id: " + id);
    }

    public FlashSaleNotFoundException(String message) {
        super(message);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
