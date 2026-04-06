package com.flashsale.backend.exception;

public class OrderNotCancellableException extends RuntimeException {

    private static final String ERROR_CODE = "ORDER_NOT_CANCELLABLE";

    public OrderNotCancellableException(String orderId, String status) {
        super("Order cannot be cancelled in status: " + status + " (orderId=" + orderId + ")");
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
