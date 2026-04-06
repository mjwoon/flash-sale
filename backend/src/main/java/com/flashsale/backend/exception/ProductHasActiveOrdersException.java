package com.flashsale.backend.exception;

public class ProductHasActiveOrdersException extends RuntimeException {

    private static final String ERROR_CODE = "PRODUCT_HAS_ACTIVE_ORDERS";

    public ProductHasActiveOrdersException(Long productId) {
        super("Product has active orders and cannot be deleted: " + productId);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
