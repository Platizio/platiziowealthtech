package com.platizio.wealthtech.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CapitalGainsReportResponse(
        UUID distributorId,
        String financialYear,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal totalSaleValue,
        BigDecimal totalPurchaseCost,
        BigDecimal totalGrandfatheredCost,
        BigDecimal shortTermCapitalGain,
        BigDecimal longTermCapitalGain,
        List<CapitalGainsLineItemResponse> lineItems
) {
}
