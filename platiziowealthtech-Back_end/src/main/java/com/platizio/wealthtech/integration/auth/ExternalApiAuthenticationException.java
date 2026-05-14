package com.platizio.wealthtech.integration.auth;

public class ExternalApiAuthenticationException extends RuntimeException {
    public ExternalApiAuthenticationException(String message) {
        super(message);
    }

    public ExternalApiAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
