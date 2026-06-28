package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body for both withdrawal endpoints
 * ({@code POST /investor/withdrawals/request-to-distributor} and
 * {@code POST /investor/withdrawals/self}). The holding is identified by either the
 * local order id or the folio; {@code mode} selects amount- or unit-based redemption
 * (Phase-2 locked decision #5 — neither preselected). {@code value} is the amount or
 * unit count; full-redemption is signalled by {@code fullRedemption=true}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WithdrawalRequest(
        UUID orderId,
        String folio,
        @NotNull WithdrawalMode mode,
        BigDecimal value,
        Boolean fullRedemption
) {
    /** Whether the redemption is expressed as a rupee amount or a number of units. */
    public enum WithdrawalMode { AMOUNT, UNITS }
}
