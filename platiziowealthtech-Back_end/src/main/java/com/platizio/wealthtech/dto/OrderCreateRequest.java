package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.TransactionType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderCreateRequest(
        @NotNull UUID investorId,
        @NotNull UUID distributorId,
        @NotNull UUID productSchemeId,
        @NotNull TransactionType transactionType,
        BigDecimal amount,
        BigDecimal units,
        String paymentMode,
        String mandateMode
) {}