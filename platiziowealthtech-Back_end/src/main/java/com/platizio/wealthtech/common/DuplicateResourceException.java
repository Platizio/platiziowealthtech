package com.platizio.wealthtech.common;

import java.util.UUID;

/**
 * Thrown when a create/update operation would violate a uniqueness rule that
 * is enforced at the application layer (before the request even reaches the DB).
 *
 * Maps to HTTP 409 Conflict via GlobalExceptionHandler.
 *
 * <p>BUG-001: when the conflict is with an existing resource the client could
 * resume (e.g. a duplicate PAN that already has an onboarding draft), the
 * existing resource id and the conflicting field can be attached so the 409
 * body can carry them and the client can pick up where it left off.
 */
public class DuplicateResourceException extends RuntimeException {

    private final UUID resourceId;
    private final String conflictField;

    public DuplicateResourceException(String message) {
        this(message, null, null);
    }

    public DuplicateResourceException(String message, UUID resourceId, String conflictField) {
        super(message);
        this.resourceId = resourceId;
        this.conflictField = conflictField;
    }

    /** Id of the existing conflicting resource, or {@code null} when not available. */
    public UUID getResourceId() {
        return resourceId;
    }

    /** Name of the conflicting field (e.g. "pan"/"email"), or {@code null} when not provided. */
    public String getConflictField() {
        return conflictField;
    }
}
