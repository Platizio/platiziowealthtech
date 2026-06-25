package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.InvestorLinkingStatus;
import java.util.UUID;

/**
 * Response for the distributor-facing skip-form endpoints (investor.md R10). Surfaces the
 * resulting {@code linkingStatus} (e.g. {@code DISTRIBUTOR_FILLING}) so the FE can reflect
 * where the link/approval choreography now stands, plus the freshly-frozen 2FA
 * {@code challengeId} the investor must approve.
 */
public record DistributorProfileSubmitResponse(
        UUID investorId,
        InvestorLinkingStatus linkingStatus,
        UUID challengeId
) {}
