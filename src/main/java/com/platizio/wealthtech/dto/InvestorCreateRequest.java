package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record InvestorCreateRequest(
        @NotNull UUID distributorId,
        @NotBlank String fullName,
        @NotBlank String mobileNumber,
        @NotBlank @Email String email,
        @NotBlank String pan,
        LocalDate dateOfBirth,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        String onboardingNotes
) {}