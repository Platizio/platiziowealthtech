package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.TransactionType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Investor-self order placement (buy funds from the investor portal). No investorId —
 * the session's linked investor is used. The owning distributor is resolved server-side
 * (orders are distributor-scoped). KYC COMPLETED + verified bank are enforced downstream.
 */
public record InvestorOrderRequest(
        @NotNull UUID productSchemeId,
        @NotNull TransactionType transactionType,
        BigDecimal amount,
        BigDecimal units,
        String paymentMode,
        String mandateMode,
        String sipFrequency,
        LocalDate sipStartDate,
        Integer sipInstalments,
        String externalSchemeCode,
        String externalIsin
) {}
