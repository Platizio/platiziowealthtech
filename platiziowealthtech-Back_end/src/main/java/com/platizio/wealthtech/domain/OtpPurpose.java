package com.platizio.wealthtech.domain;

/**
 * What an email OTP is being issued for. Kept deliberately small; new flows
 * (e.g. transaction confirmation) can be added here without schema changes.
 */
public enum OtpPurpose {
    LOGIN,
    SIGNUP
}
