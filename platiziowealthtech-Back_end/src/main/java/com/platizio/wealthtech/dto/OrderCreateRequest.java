package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.TransactionType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record OrderCreateRequest(
        @NotNull UUID investorId,
        UUID productSchemeId,
        String type,
        @NotNull TransactionType transactionType,
        BigDecimal amount,
        BigDecimal units,
        String paymentMode,
        String mandateMode,
        String sipFrequency,
        LocalDate sipStartDate,
        Integer sipInstalments,
        // SIP debit day-of-month (1–28). Optional; when set on a SIP create the start date is
        // shifted to this day (mirrors SipUpdateRequest.installmentDay used by the SIP edit flow).
        @Min(value = 1, message = "SIP date (day of month) must be between 1 and 28")
        @Max(value = 28, message = "SIP date (day of month) must be between 1 and 28")
        Integer installmentDay,
        String externalSchemeCode,
        String externalIsin
) {
        /**
         * Backward-compatible constructor for pre-{@code installmentDay} callers
         * (defaults {@code installmentDay} to null). Keeps existing positional callers
         * and tests compiling unchanged.
         */
        public OrderCreateRequest(
                UUID investorId,
                UUID productSchemeId,
                String type,
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
                this(investorId, productSchemeId, type, transactionType, amount, units, paymentMode,
                        mandateMode, sipFrequency, sipStartDate, sipInstalments, null,
                        externalSchemeCode, externalIsin);
        }

        @AssertTrue(message = "Product scheme id, external scheme code, or external ISIN is required")
        public boolean hasProductSchemeIdentifier() {
                return productSchemeId != null
                        || hasText(externalSchemeCode)
                        || hasText(externalIsin);
        }

        private static boolean hasText(String value) {
                return value != null && !value.trim().isEmpty();
        }
}
