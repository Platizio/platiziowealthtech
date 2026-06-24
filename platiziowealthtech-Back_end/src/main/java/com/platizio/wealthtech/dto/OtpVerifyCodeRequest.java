package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for the contact-verification OTP verify endpoints. */
public record OtpVerifyCodeRequest(
        @NotBlank String code
) {}
