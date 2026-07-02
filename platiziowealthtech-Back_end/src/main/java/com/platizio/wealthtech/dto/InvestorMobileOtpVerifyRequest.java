package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

/** Mobile-OTP login: verify the code sent to the registered mobile number. */
public record InvestorMobileOtpVerifyRequest(@NotBlank String mobileNumber, @NotBlank String code) {}
