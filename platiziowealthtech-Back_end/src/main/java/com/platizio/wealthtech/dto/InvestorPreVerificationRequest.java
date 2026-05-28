package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record InvestorPreVerificationRequest(
        @NotBlank
        @Size(min = 2, max = 80, message = "Investor name must be between 2 and 80 characters")
        @Pattern(
                regexp = "(?i)^(?!.*\\b(test|dummy|sample|asdf|qwerty|unknown|null|none)\\b)[a-z][a-z .'-]{1,79}$",
                message = "Enter the investor legal name as per PAN records"
        )
        String fullName,

        @NotBlank
        @Size(min = 10, max = 10, message = "PAN must be exactly 10 characters")
        @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$", message = "Invalid PAN format. Expected format: AAAAA9999A")
        String pan,

        @NotNull(message = "Date of birth is required for PAN validation")
        @Past(message = "Date of birth must be in the past")
        LocalDate dateOfBirth
) {}
