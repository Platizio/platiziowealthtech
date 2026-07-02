package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.TransactionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
        // SIP debit day-of-month (1–28). Optional; threaded into the order on create.
        @Min(value = 1, message = "SIP date (day of month) must be between 1 and 28")
        @Max(value = 28, message = "SIP date (day of month) must be between 1 and 28")
        Integer installmentDay,
        String externalSchemeCode,
        String externalIsin
) {
        /**
         * Backward-compatible constructor for pre-{@code installmentDay} callers
         * (defaults {@code installmentDay} to null).
         */
        public InvestorOrderRequest(
                UUID productSchemeId,
                TransactionType transactionType,
                BigDecimal amount,
                BigDecimal units,
                String paymentMode,
                String mandateMode,
                String sipFrequency,
                LocalDate sipStartDate,
                Integer sipInstalments,
                String externalSchemeCode,
                String externalIsin
        ) {
                this(productSchemeId, transactionType, amount, units, paymentMode, mandateMode,
                        sipFrequency, sipStartDate, sipInstalments, null, externalSchemeCode, externalIsin);
        }
}
