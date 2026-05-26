package com.platizio.wealthtech.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record HouseholdSummaryResponse(
        UUID householdId,
        String householdName,
        String primaryInvestorName,
        String primaryInvestorPan,
        int memberCount,
        long minorCount,
        long hufCount,
        BigDecimal totalOrderAmount,
        BigDecimal completedOrderAmount,
        List<HouseholdMemberResponse> members
) {
}
