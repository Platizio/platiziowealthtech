package com.platizio.wealthtech.domain;

/**
 * Lifecycle of the investor&lt;-&gt;distributor link (investor.md M1). Distinct from
 * {@link InvestorStatus}, which carries post-onboarding transaction-readiness.
 *
 * <p>The distributor enters only Step-1 basic identity and sends it to the investor;
 * the investor approves (linking {@code distributor_id} by PAN), then self-authors the
 * profile — or skips, leaving the distributor to fill it with the investor's approval
 * still required. Every later distributor edit re-enters the approval loop.
 */
public enum InvestorLinkingStatus {
    /** Distributor sent basic identity; distributor_id NOT yet linked. */
    PENDING_INVESTOR_APPROVAL,
    /** Investor approved the link; distributor_id now set by PAN. */
    INVESTOR_APPROVED,
    /** Investor is in signup -> KYC -> form. */
    INVESTOR_FILLING,
    /** Investor approved but skipped the form; distributor must fill it. */
    INVESTOR_SKIPPED,
    /** Distributor is filling an empty/skipped profile. */
    DISTRIBUTOR_FILLING,
    /** Distributor-filled details awaiting investor approval (R9/R10). */
    PENDING_PROFILE_APPROVAL,
    /** Profile complete + approved; ready for finalize. Default for existing rows. */
    READY,
    /** Investor declined the link or the proposed details. */
    REJECTED
}
