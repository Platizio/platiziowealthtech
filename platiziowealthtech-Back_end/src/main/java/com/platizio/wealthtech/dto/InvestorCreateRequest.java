package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.InvestorRelationshipType;
import com.platizio.wealthtech.validation.PanFormat;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record InvestorCreateRequest(
        @NotNull UUID distributorId,
        @NotBlank String fullName,
        @NotBlank
        @Pattern(regexp = "^[6-9]\\d{9}$",
                message = "Mobile number must be a valid 10-digit Indian number starting with 6-9")
        String mobileNumber,
        @NotBlank @Email String email,
        @NotBlank
        @Size(min = 10, max = 10, message = "PAN must be exactly 10 characters")
        @Pattern(regexp = PanFormat.INDIAN_PAN_REGEX, message = PanFormat.INDIAN_PAN_MESSAGE)
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
        @Pattern(regexp = PanFormat.INDIAN_PAN_REGEX, message = PanFormat.INDIAN_PAN_MESSAGE)
        String guardianPan,
        String onboardingNotes
) {}
