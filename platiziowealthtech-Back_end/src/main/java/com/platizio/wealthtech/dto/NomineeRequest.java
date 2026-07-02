package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Investor-supplied nominee details (REQUIREMENT #4). Carries the full canonical
 * nominee shape (shared with the distributor/IRIS flow), so a nominee captured by
 * either side is one and the same row: name, relation, allocation share, contact
 * (phone/email), identity reference (idType/idNumber), and address. No defaulted
 * values — the client sends exactly what the investor typed; allocation totals are
 * validated across all live nominees in
 * {@link com.platizio.wealthtech.service.NomineeService}.
 */
public record NomineeRequest(
        @NotBlank String fullName,
        @NotBlank String relationship,
        LocalDate dateOfBirth,
        @NotNull @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal sharePercent,
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
        Boolean sameAsApplicant,
        String guardianName
) {}
