package com.platizio.wealthtech.domain;

/**
 * Outcome of validating a distributor's AMFI Registration Number (ARN) / Know-Your-Distributor (KYD)
 * status with the configured ARN-validation provider. Distinct from {@link DistributorStatus} (which
 * tracks the account approval lifecycle) — this records only the ARN/KYD compliance verdict.
 */
public enum ArnValidationStatus {
    /** Validation not yet performed (or in progress). */
    PENDING_VERIFICATION,
    /** ARN is valid, in-date, and KYD is complete. */
    VERIFIED,
    /** ARN is not recognised / malformed / KYD failed at the provider. */
    REJECTED,
    /** ARN exists but its validity has lapsed. */
    EXPIRED,
    /** ARN is valid but the distributor's KYD is not complete. */
    KYD_INCOMPLETE
}
