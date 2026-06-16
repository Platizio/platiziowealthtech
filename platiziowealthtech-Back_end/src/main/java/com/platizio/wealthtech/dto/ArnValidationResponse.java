package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.ArnValidationStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Response for ARN validation — consumed by the distributor signup form before account creation. */
public record ArnValidationResponse(
        boolean valid,
        ArnValidationStatus status,
        String arnNumber,
        String distributorName,
        String firmName,
        LocalDate arnExpiryDate,
        String kydStatus,
        String euin,
        String source,
        OffsetDateTime validatedAt,
        String message
) {
}
