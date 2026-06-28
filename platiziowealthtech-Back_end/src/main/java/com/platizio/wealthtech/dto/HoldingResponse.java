package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Investor-scoped holding view used for the withdrawal disclosures
 * (Phase-2 plan §"Endpoint contract" {@code GET /investor/holdings}; FR-HLD/FR-RED).
 *
 * <p>{@code dataQuality} surfaces when the valuation inputs are missing or stale
 * rather than blocking, and the service NEVER falls back to the order amount or
 * zero (locked decision #4): when units or NAV are unavailable {@code currentValue}
 * is {@code null} and {@code dataQuality} is {@code UNAVAILABLE}; a NAV with no
 * fresh as-of is reported {@code STALE}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HoldingResponse(
        UUID orderId,
        String folio,
        UUID productSchemeId,
        String schemeName,
        String amcName,
        String category,
        BigDecimal availableUnits,
        BigDecimal blockedUnits,
        BigDecimal latestNav,
        OffsetDateTime navAsOf,
        BigDecimal currentValue,
        String maskedPayoutBank,
        DataQuality dataQuality,
        boolean redeemable) {

    /** Quality of the valuation inputs backing this holding row. */
    public enum DataQuality { OK, STALE, UNAVAILABLE }
}
