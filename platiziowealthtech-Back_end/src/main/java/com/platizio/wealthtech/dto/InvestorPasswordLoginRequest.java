package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.validation.PanFormat;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Investor login with email + PAN (PAN used as the password). Replaces the
 * passwordless OTP login for the investor portal.
 */
public record InvestorPasswordLoginRequest(
        @NotBlank @Email String email,
        @NotBlank
        @Pattern(regexp = PanFormat.INDIAN_PAN_REGEX, message = PanFormat.INDIAN_PAN_MESSAGE)
        String pan
) {}
