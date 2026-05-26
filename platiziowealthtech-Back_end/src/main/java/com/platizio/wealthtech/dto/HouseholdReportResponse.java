package com.platizio.wealthtech.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record HouseholdReportResponse(
        UUID distributorId,
        int householdCount,
        int memberCount,
        BigDecimal totalOrderAmount,
        BigDecimal completedOrderAmount,
        List<HouseholdSummaryResponse> households
) {
}
