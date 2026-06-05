package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.InvestorRelationshipType;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record InvestorUpdateRequest(
        String fullName,
        String mobileNumber,
        String email,
        @Size(min = 10, max = 10, message = "PAN must be exactly 10 characters")
        @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$", message = "Invalid PAN format. Expected format: AAAAA9999A")
        String pan,
        LocalDate dateOfBirth,
        LocalDate anniversaryDate,
        LocalDate goalMaturityDate,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        UUID householdId,
        String householdName,
        InvestorRelationshipType relationshipType,
        UUID guardianInvestorId,
        @Size(min = 10, max = 10, message = "Guardian PAN must be exactly 10 characters")
        @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$", message = "Invalid guardian PAN format. Expected format: AAAAA9999A")
        String guardianPan,
        String onboardingNotes
) {}
