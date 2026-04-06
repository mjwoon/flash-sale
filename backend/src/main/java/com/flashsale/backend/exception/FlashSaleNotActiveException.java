package com.flashsale.backend.exception;

public class FlashSaleNotActiveException extends RuntimeException {

    private static final String ERROR_CODE = "SALE_EVENT_NOT_ACTIVE";

    public FlashSaleNotActiveException(Long flashSaleId) {
        super("Sale event is not active: " + flashSaleId);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
