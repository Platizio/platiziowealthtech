package com.platizio.wealthtech.domain;

/**
 * Lifecycle of an {@link InvestorLinkRequest} — the token-addressed email link a
 * distributor sends so the investor can review the Step-1 basic identity and approve
 * the link (investor.md §4 V62, R3/R5). At most one {@code PENDING} request is live per
 * investor (enforced by the {@code ux_investor_link_request_live} partial unique index).
 */
public enum InvestorLinkRequestStatus {
    /** Live request awaiting the investor's approval. */
    PENDING,
    /** Investor approved; investors.distributor_id is now linked (R5). */
    APPROVED,
    /** Investor declined the link. */
    REJECTED,
    /** The 7-day window lapsed before approval. */
    EXPIRED,
    /** Replaced by a newer request for the same investor. */
    SUPERSEDED
}
