package com.platizio.wealthtech.domain;

/** Lifecycle of a distributor-assembled onboarding revision (SRS FR-ONB-002/003). */
public enum OnboardingSubmissionStatus {
    /** Frozen revision awaiting the investor's attestation. */
    DRAFT_AWAITING_INVESTOR,
    /** Investor attested this exact revision hash. */
    ATTESTED,
    /** Replaced by a newer revision or invalidated by a post-attestation edit. */
    SUPERSEDED
}
