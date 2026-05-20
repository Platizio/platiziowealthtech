package com.platizio.wealthtech.common;

/**
 * Thrown when a create/update operation would violate a uniqueness rule that
 * is enforced at the application layer (before the request even reaches the DB).
 *
 * Maps to HTTP 409 Conflict via GlobalExceptionHandler.
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
