package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for the investor onboarding attestation: the exact revision hash reviewed. */
public record OnboardingAttestRequest(
        @NotBlank String revisionHash
) {}
