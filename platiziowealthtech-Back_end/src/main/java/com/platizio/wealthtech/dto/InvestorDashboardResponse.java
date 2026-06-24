package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Rich investor holdings dashboard (Phase-3 FR-DASH). Per-holding valuation,
 * cost basis, returns and money-weighted XIRR, plus portfolio-level totals.
 *
 * <p>Distinct from the Phase-2 {@link HoldingResponse} consumed by the withdrawal
 * page — that endpoint is left untouched. As with Phase-2, valuation NEVER falls
 * back to the order amount or zero: when units or NAV are unavailable
 * {@code currentValue}/return fields are {@code null} and {@code dataQuality} is
 * {@code UNAVAILABLE}; a NAV with no fresh as-of is {@code STALE}; a 1-day return
 * with no previous-day NAV is left {@code null} (unavailable), never zeroed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvestorDashboardResponse(
        List<DashboardHolding> holdings,
        DashboardTotals totals) {

    /** Quality of the valuation inputs backing a holding row. Mirrors {@link HoldingResponse.DataQuality}. */
    public enum DataQuality { OK, STALE, UNAVAILABLE }

    /**
     * One net holding, grouped by scheme/folio (net units = settled purchase units
     * minus successfully redeemed units).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DashboardHolding(
            UUID productSchemeId,
            String schemeName,
            String amcName,
            String category,
            String folio,
            String sipName,
            BigDecimal units,
            BigDecimal latestNav,
            OffsetDateTime navAsOf,
            BigDecimal averageCostNav,
            BigDecimal invested,
            BigDecimal currentValue,
            BigDecimal absoluteReturn,
            BigDecimal percentReturn,
            BigDecimal oneDayReturn,
            BigDecimal oneDayReturnPercent,
            Double xirr,
            DataQuality dataQuality) {
    }

    /** Portfolio-level roll-up across all net holdings. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DashboardTotals(
            BigDecimal totalInvested,
            BigDecimal totalCurrentValue,
            BigDecimal totalReturn,
            BigDecimal totalReturnPercent,
            BigDecimal totalOneDayReturn,
            Double portfolioXirr) {
    }
}
