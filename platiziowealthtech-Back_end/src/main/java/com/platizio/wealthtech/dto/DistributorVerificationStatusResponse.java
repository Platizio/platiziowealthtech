package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.ArnValidationStatus;
import com.platizio.wealthtech.domain.DistributorStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Verification status for the authenticated distributor — ARN/KYD verdict plus account approval state. */
public record DistributorVerificationStatusResponse(
        UUID distributorId,
        String arnNumber,
        ArnValidationStatus arnValidationStatus,
        DistributorStatus accountStatus,
        LocalDate arnExpiryDate,
        String kydStatus,
        String firmName,
        String arnHolderName,
        String arnValidationSource,
        LocalDateTime arnValidatedAt
) {
}
