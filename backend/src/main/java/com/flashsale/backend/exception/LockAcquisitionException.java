package com.flashsale.backend.exception;

public class LockAcquisitionException extends RuntimeException {

    private static final String ERROR_CODE = "LOCK_ACQUISITION_FAILED";

    public LockAcquisitionException(String lockKey) {
        super("Failed to acquire lock for key: " + lockKey);
    }

    public LockAcquisitionException(String lockKey, Throwable cause) {
        super("Failed to acquire lock for key: " + lockKey, cause);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
