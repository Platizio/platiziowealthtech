package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for the investor approve endpoint
 * ({@code POST /investor/approvals/{challengeId}/approve}). The investor must both
 * accept the rendered consent and supply the OTP code bound to this challenge.
 */
public record ApprovalRequest(
        boolean consentAccepted,
        @NotBlank String code
) {}
