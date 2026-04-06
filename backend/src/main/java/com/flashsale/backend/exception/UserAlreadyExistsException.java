package com.flashsale.backend.exception;

public class UserAlreadyExistsException extends RuntimeException {

    private static final String ERROR_CODE = "USER_ALREADY_EXISTS";

    public UserAlreadyExistsException(String field, String value) {
        super("User already exists with " + field + ": " + value);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
