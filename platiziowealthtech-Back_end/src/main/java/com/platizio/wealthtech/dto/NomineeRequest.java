package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Investor-supplied nominee details (REQUIREMENT #4). No defaulted values — the
 * client sends exactly what the investor typed; allocation totals are validated
 * across all live nominees in {@link com.platizio.wealthtech.service.NomineeService}.
 */
public record NomineeRequest(
        @NotBlank String fullName,
        @NotBlank String relationship,
        LocalDate dateOfBirth,
        @NotNull @Min(1) @Max(100) Integer allocationPercentage,
        String addressLine,
        String guardianName
) {}
