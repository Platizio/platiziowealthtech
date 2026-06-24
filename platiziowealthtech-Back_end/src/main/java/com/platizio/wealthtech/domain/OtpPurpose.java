package com.platizio.wealthtech.domain;

/**
 * What an email OTP is being issued for. Kept deliberately small; new flows
 * (e.g. transaction confirmation) can be added here without schema changes.
 */
public enum OtpPurpose {
    LOGIN,
    SIGNUP,
    /** Passwordless investor-portal login (email OTP). */
    INVESTOR_LOGIN,
    /** Investor-portal self-signup email verification. */
    INVESTOR_SIGNUP,
    /** Investor approval of a transaction (purchase / SIP / redemption) — 2FA. */
    TRANSACTION_APPROVAL
}
