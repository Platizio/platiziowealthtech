package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

/** Mobile-OTP login: request a code for the registered mobile number. */
public record InvestorMobileOtpRequest(@NotBlank String mobileNumber) {}
