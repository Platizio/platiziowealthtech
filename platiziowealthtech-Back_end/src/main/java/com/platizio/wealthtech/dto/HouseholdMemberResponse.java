package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.InvestorRelationshipType;
import com.platizio.wealthtech.domain.InvestorStatus;
import com.platizio.wealthtech.domain.KycStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record HouseholdMemberResponse(
        UUID investorId,
        String fullName,
        String pan,
        InvestorRelationshipType relationshipType,
        UUID guardianInvestorId,
        String guardianPan,
        InvestorStatus investorStatus,
        KycStatus kycStatus,
        BankVerificationStatus bankVerificationStatus,
        BigDecimal totalOrderAmount,
        BigDecimal completedOrderAmount,
        long orderCount
) {
}
