package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for the public investor-link approve/reject endpoints (investor.md R7b).
 * The opaque {@code token} is the possession factor (delivered to the verified email);
 * {@code consentAccepted} is required for approve, ignored for reject.
 */
public record InvestorLinkActionRequest(
        @NotBlank String token,
        Boolean consentAccepted
) {}
