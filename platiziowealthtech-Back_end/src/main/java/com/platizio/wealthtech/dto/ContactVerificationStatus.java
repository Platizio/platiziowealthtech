package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.Investor;
import java.time.OffsetDateTime;

/**
 * Verification state returned by the contact-verification endpoints. {@code otpEnabled}
 * tells the UI whether a real OTP backend (Supabase) is configured; when false,
 * the disabled fallback accepts the dev code. {@code smsOtpEnabled} is the
 * per-channel flag for mobile: when false the Supabase Phone provider is off
 * (e.g. unverified / requires a paid plan), so the UI must hide mobile OTP and
 * steer the user to self-declaration.
 */
public record ContactVerificationStatus(
        boolean emailVerified,
        String emailVerificationMethod,
        String emailBelongsTo,
        OffsetDateTime emailVerifiedAt,
        boolean mobileVerified,
        String mobileVerificationMethod,
        String mobileBelongsTo,
        OffsetDateTime mobileVerifiedAt,
        boolean otpEnabled,
        boolean smsOtpEnabled
) {
    public static ContactVerificationStatus of(Investor investor, boolean otpEnabled, boolean smsOtpEnabled) {
        return new ContactVerificationStatus(
                Boolean.TRUE.equals(investor.getEmailVerified()),
                investor.getEmailVerificationMethod(),
                investor.getEmailBelongsTo(),
                investor.getEmailVerifiedAt(),
                Boolean.TRUE.equals(investor.getMobileVerified()),
                investor.getMobileVerificationMethod(),
                investor.getMobileBelongsTo(),
                investor.getMobileVerifiedAt(),
                otpEnabled,
                smsOtpEnabled);
    }
}
