package com.platizio.wealthtech.domain;

/** How an investor's email / mobile was verified. Stored on the investor. */
public enum ContactVerificationMethod {
    /** Verified via a one-time code (Supabase Auth email/SMS OTP). */
    OTP,
    /** Attested by the distributor without an OTP round-trip. */
    SELF_DECLARED
}
