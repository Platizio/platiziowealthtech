package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.ProductCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CapitalGainsLineItemResponse(
        UUID investorId,
        String investorName,
        String investorPan,
        UUID schemeId,
        String schemeName,
        String isin,
        ProductCategory assetCategory,
        LocalDate purchaseDate,
        LocalDate saleDate,
        BigDecimal units,
        BigDecimal saleValue,
        BigDecimal purchaseCost,
        BigDecimal fairMarketValueAsOf20180131,
        BigDecimal grandfatheredCost,
        BigDecimal taxableCost,
        BigDecimal capitalGain,
        CapitalGainType gainType
) {
}
