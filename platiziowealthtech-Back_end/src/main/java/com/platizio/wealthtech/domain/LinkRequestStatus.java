package com.platizio.wealthtech.domain;

/** Lifecycle of an investor link request (investor.md R3). One PENDING per investor. */
public enum LinkRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    SUPERSEDED
}
