package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.TransactionType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record OrderCreateRequest(
        @NotNull UUID investorId,
        @NotNull UUID productSchemeId,
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
) {}
