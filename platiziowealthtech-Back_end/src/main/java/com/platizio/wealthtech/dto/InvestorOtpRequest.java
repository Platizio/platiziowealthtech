package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for the investor-portal OTP request endpoint. {@code purpose} is the
 * client-facing value {@code "LOGIN"} or {@code "SIGNUP"}, mapped server-side to
 * {@link com.platizio.wealthtech.domain.OtpPurpose#INVESTOR_LOGIN} /
 * {@link com.platizio.wealthtech.domain.OtpPurpose#INVESTOR_SIGNUP}.
 */
public record InvestorOtpRequest(
        @NotBlank String email,
        @NotBlank String purpose
) {}
