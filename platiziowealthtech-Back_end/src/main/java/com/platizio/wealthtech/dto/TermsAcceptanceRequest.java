package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for recording a Terms &amp; Conditions acceptance. */
public record TermsAcceptanceRequest(
        @NotBlank String documentKey,
        @NotBlank String version
) {}
