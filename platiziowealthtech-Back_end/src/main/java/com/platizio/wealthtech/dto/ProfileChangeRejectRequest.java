package com.platizio.wealthtech.dto;

/**
 * Optional body for the investor profile-change reject endpoint
 * ({@code POST /investor/profile-changes/{challengeId}/reject}). The reason, when
 * supplied, is recorded on the audit trail; the body itself is optional.
 */
public record ProfileChangeRejectRequest(
        String reason
) {}
