package com.flashsale.backend.exception;

public class ProductNotFoundException extends RuntimeException {

    private static final String ERROR_CODE = "PRODUCT_NOT_FOUND";

    public ProductNotFoundException(Long id) {
        super("Product not found with id: " + id);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
