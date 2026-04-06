package com.flashsale.backend.exception;

public class SaleEventHaltedException extends RuntimeException {

    private static final String ERROR_CODE = "SALE_EVENT_HALTED";

    public SaleEventHaltedException(Long saleEventId) {
        super("Sale event has been halted: " + saleEventId);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
