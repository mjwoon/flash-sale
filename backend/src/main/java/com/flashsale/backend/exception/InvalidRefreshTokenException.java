package com.flashsale.backend.exception;

public class InvalidRefreshTokenException extends RuntimeException {

    private static final String ERROR_CODE = "INVALID_REFRESH_TOKEN";

    public InvalidRefreshTokenException() {
        super("Invalid or expired refresh token.");
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
