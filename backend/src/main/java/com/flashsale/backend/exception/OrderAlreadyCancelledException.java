package com.flashsale.backend.exception;

public class OrderAlreadyCancelledException extends RuntimeException {

    private static final String ERROR_CODE = "ORDER_ALREADY_CANCELLED";

    public OrderAlreadyCancelledException(String orderId) {
        super("Order is already cancelled: " + orderId);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
