package com.platizio.wealthtech.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single investor nominee on the wire (IRIS Phase 1). Carries every persisted
 * {@code investor_nominees} field. {@code nomineeIndex} is optional on input — the
 * service assigns a stable 0-based index from list order when absent.
 */
public record NomineeDto(
        Integer nomineeIndex,
        String fullName,
        LocalDate dateOfBirth,
        String relationship,
        BigDecimal sharePercent,
        String mobileNumber,
        String email,
        String idType,
        String idNumber,
        String addressLine1,
        String addressLine2,
        String addressLine3,
        String city,
        String state,
        String postalCode,
        String country,
        Boolean sameAsApplicant
) {
}
