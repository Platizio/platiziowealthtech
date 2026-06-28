package com.platizio.wealthtech.domain;

/** Lifecycle of a self-authenticated investor portal account. */
public enum InvestorAccountStatus {
    /** Created but not yet activated (both contact channels not yet satisfied). */
    PENDING_ACTIVATION,
    /** Activated; the investor can hold a session and use the portal. */
    ACTIVE,
    /** Locked by compliance/admin; cannot authenticate. */
    BLOCKED
}
