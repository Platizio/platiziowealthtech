package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

/**
 * Body for the public investor-link profile-submission endpoint (no-auth onboarding link).
 * The opaque {@code token} is the only credential (delivered to the investor's verified email).
 * The investor reviews the distributor-entered details, completes the form, and submits:
 * {@code consentAccepted} is required; {@code profile} carries the full profile fields the
 * investor authored. On success the distributor is linked, the profile applied, and
 * {@code linking_status = READY}.
 */
public record InvestorLinkProfileRequest(
        @NotBlank String token,
        boolean consentAccepted,
        JsonNode profile
) {}
