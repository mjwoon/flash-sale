package com.flashsale.backend.exception;

public class OrderNotFoundException extends RuntimeException {

    private static final String ERROR_CODE = "ORDER_NOT_FOUND";

    public OrderNotFoundException(String id) {
        super("Order not found with id: " + id);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
