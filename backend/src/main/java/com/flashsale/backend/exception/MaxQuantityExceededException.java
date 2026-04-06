package com.flashsale.backend.exception;

public class MaxQuantityExceededException extends RuntimeException {

    private static final String ERROR_CODE = "MAX_QUANTITY_EXCEEDED";

    public MaxQuantityExceededException(int maxPerUser) {
        super("Order quantity exceeds the maximum allowed per user: " + maxPerUser);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
