package com.platizio.wealthtech.dto;

import java.time.LocalDate;

public record InvestorUpdateRequest(
        String fullName,
        String mobileNumber,
        String email,
        LocalDate dateOfBirth,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        String onboardingNotes
) {}
