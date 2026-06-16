package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.TransactionType;
import jakarta.validation.constraints.AssertTrue;
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
        String externalSchemeCode,
        String externalIsin
) {
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
