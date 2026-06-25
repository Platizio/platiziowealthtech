package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Distributor-facing half of the skip-form path (investor.md R10): the profile the
 * distributor filled on behalf of an {@code INVESTOR_SKIPPED} (or already
 * {@code DISTRIBUTOR_FILLING}) investor, carried as an opaque JSON string. The acting
 * distributor is ALWAYS resolved from the JWT — never from this body.
 *
 * <p>Used by both {@code POST /api/v1/investors/{investorId}/profile/submit} and
 * {@code PUT /api/v1/investors/{investorId}/profile}.
 */
public record DistributorProfileSubmitRequest(
        @NotBlank String payloadJson
) {}
