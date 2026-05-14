package com.platizio.wealthtech.integration;

public class CybrillaApiException extends RuntimeException {
    public CybrillaApiException(String message) {
        super(message);
    }

    public CybrillaApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
