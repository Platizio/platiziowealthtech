package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for the investor-portal OTP login verify endpoint. */
public record InvestorOtpVerifyRequest(
        @NotBlank String email,
        @NotBlank String code
) {}
