package com.flashsale.backend.exception;

public class SaleEventAlreadyExistsException extends RuntimeException {

    private static final String ERROR_CODE = "SALE_EVENT_ALREADY_EXISTS";

    public SaleEventAlreadyExistsException(Long productId) {
        super("An active or scheduled sale event already exists for product: " + productId);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
