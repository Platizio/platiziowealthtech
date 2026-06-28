package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.validation.MobileFormat;
import com.platizio.wealthtech.validation.PanFormat;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Investor self-signup (SRS FR-AUTH-001/003/005). Only name/PAN/email/mobile are
 * collected; the email OTP proves inbox control; the ownership declaration and
 * T&C must be explicitly accepted (no preselected state — §12). No password.
 */
public record InvestorSignupRequest(
        @NotBlank String fullName,
        @NotBlank
        @Pattern(regexp = PanFormat.INDIAN_PAN_REGEX, message = PanFormat.INDIAN_PAN_MESSAGE)
        String pan,
        @NotBlank @Email String email,
        @NotBlank
        @Pattern(regexp = MobileFormat.INDIAN_MOBILE_REGEX, message = MobileFormat.INDIAN_MOBILE_MESSAGE)
        String mobileNumber,
        @NotBlank String emailOtp,
        @NotBlank String tncVersion,
        @AssertTrue(message = "You must confirm the email belongs to the investor.")
        boolean ownershipDeclarationAccepted,
        @AssertTrue(message = "You must accept the Terms & Conditions.")
        boolean tncAccepted
) {}
