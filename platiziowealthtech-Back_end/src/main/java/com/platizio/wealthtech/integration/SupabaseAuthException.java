package com.platizio.wealthtech.integration;

/**
 * Raised when a backend-mediated Supabase Auth call fails for an infrastructural
 * reason (network unreachable, 5xx, or a misconfiguration). A rejected OTP code
 * is NOT an exception — {@code verify*} returns {@code false} for that.
 */
public class SupabaseAuthException extends RuntimeException {

    public SupabaseAuthException(String message) {
        super(message);
    }

    public SupabaseAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
