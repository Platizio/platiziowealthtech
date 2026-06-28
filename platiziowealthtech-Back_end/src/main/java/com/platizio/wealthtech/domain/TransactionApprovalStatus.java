package com.platizio.wealthtech.domain;

/**
 * Lifecycle of a transaction-approval (2FA) challenge (Phase-2 plan §"Data model").
 *
 * <p>Happy path: {@code PENDING → CHALLENGE_SENT → APPROVED → CONSUMED}. A
 * {@code PENDING} or {@code CHALLENGE_SENT} challenge can also reach a terminal-fail
 * state ({@code EXPIRED | REJECTED | SUPERSEDED}); only a brand-new challenge can
 * re-authorize after that. A {@code CONSUMED} challenge authorizes exactly one
 * provider action.
 *
 * <p>{@link #PENDING}, {@link #CHALLENGE_SENT} and {@link #APPROVED} are the
 * "live" states: at most one live challenge may exist per transaction at a time.
 */
public enum TransactionApprovalStatus {
    PENDING,
    CHALLENGE_SENT,
    APPROVED,
    CONSUMED,
    EXPIRED,
    REJECTED,
    SUPERSEDED
}
