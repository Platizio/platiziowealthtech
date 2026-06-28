package com.platizio.wealthtech.domain;

/**
 * Lifecycle of a profile-change approval (2FA) challenge (Person-A R9/R10).
 *
 * <p>Happy path: {@code PENDING → CHALLENGE_SENT → APPROVED → CONSUMED}. A
 * {@code PENDING}, {@code CHALLENGE_SENT} or {@code APPROVED} challenge can also
 * reach a terminal-fail state ({@code EXPIRED | REJECTED | SUPERSEDED}); only a
 * brand-new challenge can re-authorize after that. A {@code CONSUMED} challenge
 * authorizes exactly one profile-apply.
 *
 * <p>{@link #PENDING}, {@link #CHALLENGE_SENT} and {@link #APPROVED} are the
 * "live" states: at most one live challenge may exist per investor at a time.
 */
public enum ProfileChangeApprovalStatus {
    PENDING,
    CHALLENGE_SENT,
    APPROVED,
    CONSUMED,
    EXPIRED,
    REJECTED,
    SUPERSEDED
}
