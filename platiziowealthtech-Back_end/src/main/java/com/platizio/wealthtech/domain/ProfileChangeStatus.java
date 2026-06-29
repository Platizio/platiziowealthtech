package com.platizio.wealthtech.domain;

/**
 * Status machine for a profile-change approval challenge (investor.md R9/R10),
 * mirroring {@link TransactionApprovalStatus}: PENDING -> CHALLENGE_SENT -> APPROVED
 * -> CONSUMED; PENDING|CHALLENGE_SENT -> EXPIRED|REJECTED|SUPERSEDED. CONSUMED
 * authorizes exactly one apply of the proposed profile.
 */
public enum ProfileChangeStatus {
    PENDING,
    CHALLENGE_SENT,
    APPROVED,
    CONSUMED,
    SUPERSEDED,
    EXPIRED,
    REJECTED
}
