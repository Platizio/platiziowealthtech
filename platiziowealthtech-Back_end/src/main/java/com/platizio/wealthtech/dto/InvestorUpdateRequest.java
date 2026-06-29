package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.InvestorRelationshipType;
import com.platizio.wealthtech.validation.MobileFormat;
import com.platizio.wealthtech.validation.PanFormat;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvestorUpdateRequest(
        String fullName,
        @Pattern(regexp = MobileFormat.INDIAN_MOBILE_REGEX, message = MobileFormat.INDIAN_MOBILE_MESSAGE)
        String mobileNumber,
        String email,
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
        String onboardingNotes,
        // IRIS rich onboarding fields (Phase 1).
        String holdingMode,
        String category,
        String gender,
        String countryOfBirth,
        String countryOfCitizenship,
        Boolean taxResidentOtherCountry,
        String annualIncome,
        String occupation,
        String sourceOfWealth,
        Boolean pep,
        Boolean relativeOfPep,
        Boolean displayNominees,
        List<NomineeDto> nominees
) {}
