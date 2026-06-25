package com.platizio.wealthtech.domain;

/**
 * Drives the investor↔distributor linking + approval choreography (investor.md §3),
 * tracked on the {@code investors.linking_status} column. Distinct from
 * {@link InvestorStatus} (which keeps its transaction-readiness meaning). PAN is the
 * sole investor↔distributor identifier (R4); {@code distributor_id} is linked only at
 * the {@code INVESTOR_APPROVED} transition (R5).
 */
public enum InvestorLinkingStatus {
    /** Distributor sent basic identity; distributor_id NOT yet linked (pending). */
    PENDING_INVESTOR_APPROVAL,
    /** Investor clicked Approve; distributor_id now linked by PAN. */
    INVESTOR_APPROVED,
    /** Investor in signup → KYC → form. */
    INVESTOR_FILLING,
    /** Investor approved but skipped the form; distributor must fill it (R10). */
    INVESTOR_SKIPPED,
    /** Distributor filling an empty/skipped profile (R10). */
    DISTRIBUTOR_FILLING,
    /** Distributor-filled details awaiting investor approval (R9/R10). */
    PENDING_PROFILE_APPROVAL,
    /** Profile complete + approved; investor ACTIVE; ready to finalize. */
    READY,
    /** Investor declined the link/details (terminal-ish; distributor may re-send). */
    REJECTED
}
