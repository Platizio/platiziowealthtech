package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.InvestorRelationshipType;
import com.platizio.wealthtech.validation.MobileFormat;
import com.platizio.wealthtech.validation.PanFormat;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvestorCreateRequest(
        @NotNull UUID distributorId,
        @NotBlank String fullName,
        @NotBlank
        @Pattern(regexp = MobileFormat.INDIAN_MOBILE_REGEX, message = MobileFormat.INDIAN_MOBILE_MESSAGE)
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
        List<NomineeDto> nominees,
        // investor.md R1/R5: when true, create a pending investor (distributor_id NULL,
        // pending_distributor_id set, linking_status PENDING_INVESTOR_APPROVAL) for the
        // "Send to Investor" flow. Defaults to false to preserve the legacy create path.
        Boolean gatedOnInvestorApproval
) {
    /**
     * IRIS-order constructor (the canonical 32-field form used by onboarding call sites
     * and existing tests): defaults the linking flag to {@code null} (legacy, non-gated
     * distributor-owned create path).
     */
    public InvestorCreateRequest(
            UUID distributorId, String fullName, String mobileNumber, String email, String pan,
            LocalDate dateOfBirth, LocalDate anniversaryDate, LocalDate goalMaturityDate,
            String addressLine1, String addressLine2, String city, String state, String postalCode,
            UUID householdId, String householdName, InvestorRelationshipType relationshipType,
            UUID guardianInvestorId, String guardianPan, String onboardingNotes,
            String holdingMode, String category, String gender, String countryOfBirth,
            String countryOfCitizenship, Boolean taxResidentOtherCountry, String annualIncome,
            String occupation, String sourceOfWealth, Boolean pep, Boolean relativeOfPep,
            Boolean displayNominees, List<NomineeDto> nominees) {
        this(distributorId, fullName, mobileNumber, email, pan, dateOfBirth, anniversaryDate, goalMaturityDate,
                addressLine1, addressLine2, city, state, postalCode, householdId, householdName, relationshipType,
                guardianInvestorId, guardianPan, onboardingNotes, holdingMode, category, gender, countryOfBirth,
                countryOfCitizenship, taxResidentOtherCountry, annualIncome, occupation, sourceOfWealth, pep,
                relativeOfPep, displayNominees, nominees, null);
    }
}
